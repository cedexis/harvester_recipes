import com.mashape.unirest.http.Unirest
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat

/**
 * Created by ben on 1/28/14.
 */
@Grapes([
@Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3'),
@Grab(group = 'joda-time', module = 'joda-time', version = '2.3')
])
@Slf4j
class Edgecast {

    def run(config) {

        new JsonBuilder([
                bandwidth: [unit: "Mbps", value: get_bandwidth(config)],
                usage: [unit: "GB", value: get_usage(config)]
        ]).toPrettyString()
    }

    def get_bandwidth(config) {

        def startDate = DateTime.now(DateTimeZone.UTC)
                .withDayOfMonth(1)
                .toString(DateTimeFormat.forPattern("YYYY-MM-dd"))

           def bandwidth = [
                2: "Flash Media Streaming",
                3: "HTTP Large",
                8: "HTTP Small",
                1: "Windows Media Streaming",
                7: "HTTP Large (SSLTrafficOnly)",
                9: "HTTP Small (SSLTrafficOnly)",
                14: "Application Delivery Network (ADN)",
                15: "Application Delivery Network (ADN) – (SSLTrafficOnly)",
            ].collectEntries {
               /*
                can't use this endpoint, because it throws an error if the customer doesn't have realtimestats enabled in the account
                def response = Unirest.get("https://api.edgecast.com/v2/realtimestats/customers/$config.account_number/media/$it.key/bandwidth?begindate=${startDate}&").headers([
                       "Authorization": "TOK:$config.rest_api_token" as String,
                       "Accept": "application/json" as String
               ]).asString()
                */

                // region/-1 = global (all billing regions), units/1 = Bandwidth metric
                def response = Unirest.get("https://api.edgecast.com/v2/reporting/customers/$config.account_number/media/$it.key/region/-1/units/1/trafficusage?begindate=${startDate}").headers([
                "Authorization": "TOK:$config.rest_api_token" as String,
                "Accept": "application/json" as String
                ]).asString()

                if (response.code == 200) {
                   def mbps = new JsonSlurper().parseText(response.body).UsageResult
                   return [(it.key as Integer): [
                           "platform": it.value,
                           "mbps": mbps
                   ]]
                }

            [(it.key as Integer):[:]]
        }.collect {
            it.value.mbps ? it.value.mbps : 0
        }.sum()

        bandwidth ? String.format("%.2f", bandwidth) : "0"
    }

    def get_usage(config) {

        def startDate = DateTime.now(DateTimeZone.UTC)
                .withDayOfMonth(1)
                .toString(DateTimeFormat.forPattern("YYYY-MM-dd"))

        // /region/-1 = global, all regions, units/2 = GB Data Transferred
        def dataTransferred = [
                2: "Flash Media Streaming",
                3: "HTTP Large",
                8: "HTTP Small",
                1: "Windows Media Streaming",
                7: "HTTP Large (SSLTrafficOnly)",
                9: "HTTP Small (SSLTrafficOnly)",
                14: "Application Delivery Network (ADN)",
                15: "Application Delivery Network (ADN) – (SSLTrafficOnly)",
        ].collectEntries {
            def response = Unirest.get("https://api.edgecast.com/v2/reporting/customers/$config.account_number/media/$it.key/region/-1/units/2/trafficusage?begindate=${startDate}").headers([
                    "Authorization": "TOK:$config.rest_api_token" as String,
                    "Accept": "application/json" as String
            ]).asString()

            // region/-1 = global (all billing regions), units/2 = GB Data Transferred
            if (response.code == 200) {
                def gb = new JsonSlurper().parseText(response.body).UsageResult
                return [(it.key as Integer): [
                        "platform": it.value,
                        "gb": gb
                ]]
            }

            [(it.key as Integer):[:]]
        }.collect {
            it.value.gb ? it.value.gb : 0
        }.sum()

        dataTransferred ? String.format("%.2f", dataTransferred) : "0"

    }

    def auth(config) {

	    def valid = true
        def startDate = DateTime.now(DateTimeZone.UTC)
                .withDayOfMonth(1)
                .withTimeAtStartOfDay()
                .toString(DateTimeFormat.forPattern("YYYY-MM-dd'T'HH:mm:ss"))

        def endDate = DateTime.now(DateTimeZone.UTC)
                .toString(DateTimeFormat.forPattern("YYYY-MM-dd'T'HH:mm:ss"))

        def response = Unirest.get("https://api.edgecast.com/v2/reporting/customers/$config.account_number/bytestransferred?begindate=${startDate}&enddate=${endDate}").headers([
                    "Authorization": "TOK:$config.rest_api_token" as String,
                    "Accept": "application/json" as String
            ]).asString()

	    if (response.body.contains("Invalid account number value") || response.body.contains("Access Denied")) {
		    throw new RuntimeException("Invalid Credentials")
	    } else {
		    return true
	    }
    }

    @SuppressWarnings(["GroovyUnusedDeclaration", "GrMethodMayBeStatic"])
    def getCustomName(config) {
        if(config.customName) {
            return config.customName
        }

        return ""
    }

    def recipe_config() {
        [
                name: "Edgecast",
                description: "Bandwidth and usage metrics",
                identifier: "x.account_number",
                run_every: 3600,
                feed_types: ["usage", "bandwidth"],
                fields:
                        [
                                ["name": "customName", "displayName": "Name", "fieldType": "text", "i18n":"name", source: "getCustomName", "optional":"true"],
                                ["name": "account_number", "displayName": "Account Number", "fieldType": "text", "i18n":"accountNumber"],
                                ["name": "rest_api_token", "displayName": "REST API Token", "fieldType": "text", "i18n":"apiToken", "extended_type":"password"]
                        ],
                screens:
                        [
                                [
                                        header: "Enter your Edgecast Credentials",
                                        fields: ["customName", "rest_api_token", "account_number"],
					                    submit: "auth"
                                ]
                        ]
        ]
    }
}
