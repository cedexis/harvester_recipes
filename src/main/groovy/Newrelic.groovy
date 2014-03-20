import com.mashape.unirest.http.Unirest
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

@Grapes([
@Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3'),
@Grab(group = 'joda-time', module = 'joda-time', version = '2.3')
])
class Newrelic {

    def run(config) {
        def response = Unirest.get("https://api.newrelic.com/v2/servers.json").header("X-Api-Key", config.api_key).asString()

        println "$response.code https://api.newrelic.com/v2/servers.json"

        def data = new JsonSlurper().parseText(response.body)

        def result = data.servers.collect {
            def label = it.host.replaceAll("\\.", "_")

            def units = ["cpu": "%",
                    "cpu_stolen": "%",
                    "disk_io": "%",
                    "memory": "%",
                    "fullest_disk": "%"]

            if (it.reporting) {
                return it.summary.subMap(["cpu",
                        "cpu_stolen",
                        "disk_io",
                        "memory",
                        "fullest_disk"]).collectEntries {
                    ["${label}_${it.key}": [
                            "unit": units.get(it.key),
                            "value": it.value
                    ]
                    ]
                }
            }
            [:]
        }.sum()

        new JsonBuilder(result).toPrettyString()
    }

    def auth(config) {
        def response = Unirest.get("https://api.newrelic.com/v2/servers.json").header("X-Api-Key", config.api_key).asString()
        if (response.code == 200) {
            return true
        }
        new RuntimeException("Invalid Credentials")
    }

    def recipe_config() {
        [
                name: "New Relic",
                description: "Server Monitoring",
                identifier: "x.api_key",
                run_every: 3600,
                fields:
                        [
                                ["name": "api_key", "displayName": "API key", "fieldType": "text"]
                        ],
                screens:
                        [
                                [
                                        header: "Enter your New Relic Credentials",
                                        fields: ["api_key"],
                                        submit: "auth"
                                ]
                        ]
        ]
    }


}