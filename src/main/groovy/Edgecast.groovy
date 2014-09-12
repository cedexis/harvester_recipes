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
                .withTimeAtStartOfDay()
                .toString(DateTimeFormat.forPattern("YYYY-MM-dd'T'HH:mm:ss"))

        def endDate = DateTime.now(DateTimeZone.UTC)
                .toString(DateTimeFormat.forPattern("YYYY-MM-dd'T'HH:mm:ss"))

        def response = Unirest.get("https://api.edgecast.com/v2/reporting/customers/$config.account_number/bytestransferred?begindate=${startDate}&enddate=${endDate}").headers([
                "Authorization": "TOK:$config.rest_api_token" as String,
                "Accept": "application/json" as String
        ]).asString()

        // {"Bytes":9805546316569858}
        if( response.body) {
            def result = new JsonSlurper().parseText(response.body)
            def MBps = result.Bytes ? (result.Bytes / 1048576) : 0

            // Convert mega bytes/sec to Mbps
            return String.format("%.2f", (MBps * 8))
        }

        throw new RuntimeException("No results found for Edgecast /bytestransferred API endpoint request")

    }

    def get_usage(config) {

        def startDate = DateTime.now(DateTimeZone.UTC)
                .withDayOfMonth(1)
                .withTimeAtStartOfDay()
                .toString(DateTimeFormat.forPattern("YYYY-MM-dd'T'HH:mm:ss"))

        def endDate = DateTime.now(DateTimeZone.UTC)
                .toString(DateTimeFormat.forPattern("YYYY-MM-dd'T'HH:mm:ss"))

        def response = Unirest.get("https://api.edgecast.com/v2/reporting/customers/$config.account_number/maxstorageusage?begindate=${startDate}&enddate=${endDate}").headers([
                "Authorization": "TOK:$config.rest_api_token" as String,
                "Accept": "application/json" as String
        ]).asString()

        // {"UsageResult":4177.67}  GB
        if( response.body) {
            def result = new JsonSlurper().parseText(response.body)
            def gb = result.UsageResult

            // Convert mega bytes/sec to Mbps
            return String.format("%.2f", gb)
        }

        throw new RuntimeException("No results found for Edgecast /maxstorageusage API endpoint request")

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

    def recipe_config() {
        [
                name: "Edgecast",
                description: "Bandwidth and usage metrics",
                identifier: "x.account_number",
                run_every: 3600,
                fields:
                        [
                                ["name": "account_number", "displayName": "Account Number", "fieldType": "text"],
                                ["name": "rest_api_token", "displayName": "REST API Token", "fieldType": "text"]
                        ],
                screens:
                        [
                                [
                                        header: "Enter your Edgecast Credentials",
                                        fields: ["rest_api_token", "account_number"],
					                    submit: "auth"
                                ]
                        ]
        ]
    }
}
