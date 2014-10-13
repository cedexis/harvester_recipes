import com.mashape.unirest.http.Unirest
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

/**
 *
 * New Relic API docs: https://docs.newrelic.com/docs/apis/
 * New Relic API explorer:  https://rpm.newrelic.com/api/explore  (after logged in to account)
 *
 * Servers list:  curl -X GET 'https://api.newrelic.com/v2/servers.json' -H 'X-Api-Key:[your_api_key_here]' -i
 * Applications List: curl -X GET 'https://api.newrelic.com/v2/applications.json' -H 'X-Api-Key:[your_api_key_here]' -i
 *
 * @author grillo
 * @since 5/2014
 *
 */
@Grapes([
@Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3'),
@Grab(group = 'joda-time', module = 'joda-time', version = '2.3')
])
class NewrelicApdex {

    def NEWRELIC_API_V2 = 'https://api.newrelic.com/v2'

    def apdex_countries=['A1','A2','AD','AE','AF','AG','AL','AM','AN','AO','AP','AR','AT','AU','AW','AX','AZ','BA','BB','BD','BE','BF','BG','BH','BI','BJ','BM','BN','BO','BR','BS','BT','BW','BY','BZ','CA','CD','CF','CH','CI','CL','CM','CN','CO','CR','CV','CY','CZ','DE','DJ','DK','DM','DO','DZ','EC','EE','EG','ER','ES','ET','EU','FI','FJ','FO','FR','GA','GB','GD','GE','GF','GG','GH','GI','GL','GM','GN','GP','GQ','GR','GT','GU','GY','HK','HN','HR','HT','HU','ID','IE','IL','IM','IN','IQ','IR','IS','IT','JE','JM','JO','JP','KE','KG','KH','KN','KR','KW','KY','KZ','LA','LB','LC','LI','LK','LR','LS','LT','LU','LV','LY','MA','MC','MD','ME','MG','MK','ML','MM','MN','MO','MQ','MR','MT','MU','MV','MW','MX','MY','MZ','NA','NE','NG','NI','NL','NO','NP','NZ','OM','PA','PE','PF','PG','PH','PK','PL','PR','PS','PT','PY','QA','RE','RO','RS','RU','RW','SA','SB','SC','SD','SE','SG','SI','SK','SL','SM','SN','SO','SR','ST','SV','SY','SZ','TC','TG','TH','TJ','TL','TM','TN','TR','TT','TW','TZ','UA','UG','US','UY','UZ','VA','VC','VE','VG','VI','VN','VU','YE','YT','ZA','ZM','ZW']

    def run(config) {

        def applications = getApplications(config)
        getApdexScores(config, applications)

        new JsonBuilder(applyApdexRules(applications)).toPrettyString()

    }

    def getApplications(config) {
        def response = Unirest.get("$NEWRELIC_API_V2/applications.json").header("X-Api-Key", config.api_key).asString()

        def data = new JsonSlurper().parseText(response.body)

        if( data && (data.size() == 0 || data.applications.size() == 0)) {
            throw new Exception("API key is valid but no New Relic applications found")
        }

        def applications = data.applications.collect {
            def name = it.name
            if( name.contains(config.app_name) && it.reporting) {
                if (it.health_status == 'green') {
                    return [
                            "apdex_threshold" : it.settings.app_apdex_threshold,
                            "application_summary": it.application_summary,
                            "id": it.id,
                            "platform":create_om_alias(it.name)
                    ]
                }
            }
        }.findAll{it}

        applications
    }

    def create_om_alias(name) {
        def alias = name.replaceAll("\\W", "_")

        if( alias.endsWith("_") ){
            alias = alias.substring(0, alias.length()-1)
        }

        alias
    }

    def getApdexScores(config, applications) {

        def country, score
        def requestBody = getRequestBody()

        applications.each { application ->

            def response = Unirest.post("$NEWRELIC_API_V2/applications/$application.id/metrics/data.json")
                    .header("X-Api-Key", config.api_key)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body(requestBody)
                    .asString()

            def data = new JsonSlurper().parseText(response.body)

            if (data && data.size() > 0) {
                def apdex_scores = [:]
                data.metric_data.metrics.each { metric ->
                    country = metric.name.substring(metric.name.lastIndexOf("/") + 1)
                    score = metric.timeslices.collect {it.values['score']}
                    if( country && score) apdex_scores.put(country, score.get(0))
                }
                if( apdex_scores && apdex_scores.size() > 0) application.put("apdex_scores", apdex_scores)
            }
        }
    }

    def applyApdexRules( applications ) {

        def inconsistent_applications = []

        applications.each{ application ->
            // scores is a Map<BigDecimal,Integer) where BigDecimal is a unique apdex score (i.e. 1.0, .50, etc)
            // and Integer is the number of times the score occurs in the apdex country results
            def scores = sortApdexScoresByValue(application)

            // are apdex scores consistent (70% of scores are one value)
            if( ! isApdexScoresConsistent(scores)) {
                inconsistent_applications.add(application)
            }

            // missing countries are those countries not included in the application apdex country scores
            application.put("apdex_countries_na", getMissingCountries(application))

            // countries below minimum are those countries with apdex score less than the median score
            application.put("apdex_countries_below_minimum", getCountriesBelowMinimum(application))

            // we want to save space in the OM data feed (must be under 10K)
            // now that we are done with the apdex scores, remove them from the application
            application.remove('apdex_scores')
        }

        inconsistent_applications.each{ application ->
            applications.remove(application)
        }

        def provider_apps = [:]
        def platform
        applications.each { app ->
            platform = app.platform
            app.remove('platform')
            provider_apps.put(platform, app)
        }
        provider_apps

    }

    def isApdexScoresConsistent(scores) {
        def sum = scores.values().toList().sum()
        def first = scores.values().toList().first()
        def consistent = false
        // 70% of the scores should be what the best score is to validate scores are consistent
        if( (first / sum) * 100 >= 70) {
            consistent = true
        }

        consistent
    }

    def sortApdexScoresByValue(application) {
        def scores = [:]
        application.apdex_scores.each{ k,v ->
            if(scores.containsKey(v)) {
                scores.put(v, (scores.get(v) + 1) )
            }else {
                scores.put(v, 1)
            }

        }
        // sort map by values descending
        scores.sort{ -it.value }
    }

    def getMissingCountries(application){
        def app_countries = application.apdex_scores.keySet()
        return (( apdex_countries + app_countries ) - app_countries.intersect( apdex_countries ))
    }

    def getCountriesBelowMinimum(application) {
        def below_minimum_countries = []
        application.apdex_scores.each{ k,v ->
            if( v < application.apdex_threshold) below_minimum_countries.add(k)
        }

        below_minimum_countries
    }

    def getRequestBody() {

        def body = ""
        apdex_countries.each{
            body += "names[]=EndUser/Apdex/Country/$it&"
        }
        body += "values=score&"
        body += "summarize=true"

        body
    }

    def auth(config) {
        def response = Unirest.get("https://api.newrelic.com/v2/servers.json").header("X-Api-Key", config.api_key).asString()
        if (response.code == 200) {
            return true
        }
        throw new RuntimeException("Invalid Credentials")
    }

    def recipe_config() {
        [
                name: "NR Apdex",
                description: "New Relic Application Apdex Country Scores",
                identifier: "x.api_key",
                run_every: 3600,
                fields:
                        [
                                ["name": "api_key", "displayName": "API key", "fieldType": "text"],
                                ["name": "app_name", "displayName": "Application Name (matches any part of app name)", "fieldType": "text"]
                        ],
                screens:
                        [
                                [
                                        header: "Enter your New Relic Credentials and Application",
                                        fields: ["api_key", "app_name"],
                                        submit: "auth"
                                ]
                        ]
        ]
    }


}

