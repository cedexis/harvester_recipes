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
class Newrelic {

    def run(config) {
        def response = Unirest.get("https://api.newrelic.com/v2/servers.json").header("X-Api-Key", config.api_key).asString()

        println "$response.code https://api.newrelic.com/v2/servers.json"

        def data = new JsonSlurper().parseText(response.body)

        if( data && (data.size() == 0 || data.servers.size() == 0)) {
            throw new Exception("API key is valid but no New Relic servers found")
        }

        def result = data.servers.collect {
            def label = it.host.replaceAll("\\.", "_")

            def units = [
                    "cpu": "%",
                    "cpu_stolen": "%",
                    "disk_io": "%",
                    "memory": "%",
                    "fullest_disk": "%",
                    "health":"(0-5)"
            ]

            if (it.reporting) {
                def calc_health = it.summary
                calc_health.put('health_status',it.health_status)

                def value = it.health_status == '' ? 0 : compute_health_diagnostic(calc_health)
                it.summary << ["health": value]
                return it.summary.subMap(["cpu",
                        "cpu_stolen",
                        "disk_io",
                        "memory",
                        "fullest_disk",
                        "health"
                ]).collectEntries {
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

    def compute_health_diagnostic(data) {

        // health status: green - good, yellow - caution (non critical issue)
        // red - critical alert or NR agent is not communicating
        // gray - no data being report for the server at this time
        if ((! data.health_status.toLowerCase().contains("red"))
                && (data.cpu ? data.cpu <= 20 : true)
                && (data.cpu_stolen ? data.cpu_stolen  <= 15 : true)
                && (data.memory ? data.memory <= 30 : true)
                && (data.fullest_disk ? data.fullest_disk  <= 30 : true)) {
            return "5"
        }

        // all must be true
        if ((! data.health_status.toLowerCase().contains("red"))
                && (data.cpu ? data.cpu  <= 50 : true)
                && (data.cpu_stolen ? data.cpu_stolen  <= 20 : true)
                && (data.memory ? data.memory  <= 50 : true)
                && (data.fullest_disk ? data.fullest_disk  <= 50 : true)) {
            return "4"
        }

        // if all are true, most likely fine to route traffic
        if ((! data.health_status.toLowerCase().contains("red"))
                && (data.cpu ? data.cpu  <= 70 : true)
                && (data.cpu_stolen ? data.cpu_stolen  <= 30 : true)
                && (data.memory ? data.memory  <= 70 : true)
                && (data.fullest_disk ? data.fullest_disk  <= 70 : true)) {
            return "3"
        }

        // if all are true, we are approaching an usable server
        if ((! data.health_status.toLowerCase().contains("red"))
                && (data.cpu ? data.cpu  <= 95 : true)
                && (data.cpu_stolen ? data.cpu_stolen  <= 60 : true)
                && (data.memory ? data.memory  <= 95 : true)
                && (data.fullest_disk ? data.fullest_disk <= 80 : true)) {
            return "2"
        }


        // if any true, it's up but it will be very slow
        if ((! data.health_status.toLowerCase().contains("red"))
                && (data.cpu ? data.cpu  < 150 : true)
                && (data.cpu_stolen ? data.cpu_stolen  < 75 : true)
                && (data.memory ? data.memory  < 150 : true)
                && (data.fullest_disk ? data.fullest_disk  < 95 : true)) {
            return "1"
        }

        // Server is down or unusable, don't route traffic
        "0"
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

