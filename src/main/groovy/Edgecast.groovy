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

    def config = [:]

    def run(config) {
        this.config = config
        new JsonBuilder([
                bandwidth: [unit: "Mbps", value: get_bandwidth()],
                usage: [unit: "GB", value: get_usage()]
        ]).toPrettyString()
    }

    def get_bandwidth() {

        def bandwidth = [
                2: "Flash Media Streaming",
                3: "HTTP Large",
                8: "HTTP Small"
        ].collectEntries {
            def month = DateTime.now(DateTimeZone.UTC).withDayOfMonth(1).toString(DateTimeFormat.forPattern("YYYY-MM-dd"))
            def response = Unirest.get("https://api.edgecast.com/v2/realtimestats/customers/$config.account_number/media/$it.key/bandwidth?begindate=${month}").headers([
                    "Authorization": "TOK:$config.rest_api_token" as String,
                    "Accept": "application/json" as String
            ]).asString()

            if (response.code == 200) {
                def bps = new JsonSlurper().parseText(response.body).Result
                return [(it.key as Integer): [
                        "platform": it.value,
                        "mbps": bps / 1048576
                ]]
            } else if (response.code == 403) {
                throw new RuntimeException(new JsonSlurper().parseText(response.body).Message)
            }

            [(it.key as Integer):[:]]
        }.collect {
            it.value.mbps ? it.value.mbps : 0
        }.sum()

        bandwidth ? String.format("%.2f", bandwidth) : "0"
    }

    def get_usage() {

        def usage = [
                2: "Flash Media Streaming",
                3: "HTTP Large",
                8: "HTTP Small",
                1: "Windows Media Streaming",
                7: "HTTP Large (SSLTrafficOnly)",
                9: "HTTP Small (SSLTrafficOnly)",
                14: "Application Delivery Network (ADN)",
                15: "Application Delivery Network (ADN) – (SSLTrafficOnly)",
        ].collectEntries {
            def month = DateTime.now(DateTimeZone.UTC).withDayOfMonth(1).toString(DateTimeFormat.forPattern("YYYY-MM-dd"))
            def response = Unirest.get("https://api.edgecast.com/v2/reporting/customers/$config.account_number/media/$it.key/region/-1/units/1/trafficusage?begindate=${month}").headers([
                    "Authorization": "TOK:$config.rest_api_token" as String,
                    "Accept": "application/json" as String
            ]).asString()

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

        usage ? String.format("%.2f", usage) : "0"

    }

    def auth(config) {
        def usage = [
                2: "Flash Media Streaming",
                3: "HTTP Large",
                8: "HTTP Small",
                1: "Windows Media Streaming",
                7: "HTTP Large (SSLTrafficOnly)",
                9: "HTTP Small (SSLTrafficOnly)",
                14: "Application Delivery Network (ADN)",
                15: "Application Delivery Network (ADN) – (SSLTrafficOnly)",
        ]
	def valid = true
	usage.collect { it.value }.each { media_type ->
	  try{
            def month = DateTime.now(DateTimeZone.UTC).withDayOfMonth(1).toString(DateTimeFormat.forPattern("YYYY-MM-dd"))
            def response = Unirest.get("https://api.edgecast.com/v2/reporting/customers/$config.account_number/media/${media_type}/region/-1/units/1/trafficusage?begindate=${month}").headers([
                    "Authorization": "TOK:$config.rest_api_token" as String,
                    "Accept": "application/json" as String
            ]).asString()
	    if (response.body.contains("Invalid account number value") || response.body.contains("Access Denied")) {
		valid = false
	    }
	  } catch (Exception e) {
                this.log.error("", e)
          }
        }
	if (!valid) {
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
