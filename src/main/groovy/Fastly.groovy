import com.mashape.unirest.http.Unirest
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat

@Grapes([
@Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3'),
@Grab(group='joda-time', module='joda-time', version='2.3')
])
@Slf4j
class Fastly {

    def API_URL = "https://api.fastly.com/stats/usage"

    def run(config) {

        def report = [:]

        def start  = DateTime.now(DateTimeZone.UTC).withDayOfMonth(1).toString(DateTimeFormat.forPattern("MM/dd/YYYY"))
        def end  = DateTime.now(DateTimeZone.UTC).plusMonths(1).withDayOfMonth(1).toString(DateTimeFormat.forPattern("MM/dd/YYYY"))

        def response = Unirest.get(API_URL).fields([from:start, to:end]).headers(["Fastly-Key": config.api_key as String]).asString()
        if (response.code == 200) {
            def payload = new JsonSlurper().parseText(response.body)
            report["usage"] = [
                    unit: "GB",
                    value: String.format("%.2f", payload.data.collect { (it.value as Map).bandwidth as Long }.sum() / ((1024L * 1024L) * 1024L))
            ]
        }

        new JsonBuilder(report).toPrettyString()
    }

    def auth(config) {
        def response = Unirest.get(API_URL).headers(["Fastly-Key": config.api_key as String]).asString()
        if (response.code != 200) {
            throw new RuntimeException("Invalid Credentials")
        }
        true
    }

    def gauge(json) {
        def data = new JsonSlurper().parseText(json)
        data && data.usage ? "${data.usage.unit} ${String.format("%.2f", data.usage.value)}" : ""
    }

    def recipe_config() {
        [
                name: "Fastly",
                description: "Usage metrics",
                run_every: 3600,
                identifier: "x.api_key",
                fields:
                        [
                                ["name": "api_key", "displayName": "API Key", "fieldType": "text"]
                        ],
                screens:
                        [
                                [
                                        header: "Enter your Fastly Credentials",
                                        fields: ["api_key"],
                                        submit: "auth"
                                ]
                        ]
        ]
    }


}
