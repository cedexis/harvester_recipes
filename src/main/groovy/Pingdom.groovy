import com.mashape.unirest.http.Unirest
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
/**
 * @author damian
 */
@Grapes([
@Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3')
])
@Slf4j
class Pingdom {

    def REST_URL = "https://api.pingdom.com/api/2.0/checks"

    def run(config) {
        def result = configure_pingdom_check(config)
        new JsonBuilder(result).toPrettyString()
    }

    def get_checks_list(config) {
        def response = Unirest.get(REST_URL)
            .header("App-Key", config['appkey'])
            .basicAuth(config['username'], config['password']).asString()
		
        check_response(response)
        def checks_result = new JsonSlurper().parseText(response.body)
        return checks_result
    }

    def create_new_check(config) {
        def response = Unirest.post(REST_URL)
            .header("App-Key", config['appkey'])
            .field("name", "fusion check")
            .field("type", "http")
            .field("host", config['destination'])
            .basicAuth(config['username'], config['password']).asString()
        check_response(response)
    }

    def configure_pingdom_check(config) {
        def checks_list = get_checks_list(config)

        def exists = false
        checks_list.checks.each { it ->
            if (it.name == "fusion check" && it.hostname == config['destination']) {
                exists = true
            }
        }

        if (!exists) {
            create_new_check(config)
        }

        def result = [:]
        result["is_alive"] = ["value": "true", "unit": "boolean", "bypass_data_points":"true"]
        return result
    }

    def auth(config) {
        try {
            def response = Unirest.get(REST_URL)
                .header("App-Key", config['appkey'])
                .basicAuth(config['username'], config['password']).asString()
	    
            if (response.code != 200) {
                throw new RuntimeException("Invalid Credentials")
            }
            return true
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage())
        }
    }

    def check_response(response) {
        if (response.code != 200) {
            throw new RuntimeException("Unable to process pingdom request, received " + response.code + " response from API call")
        }
    }

    def recipe_config() {
        [
            name: "Pingdom",
            description: "HTTP server availability check",
            identifier: "x.destination",
            run_every: 60,
            no_platform: true,
            fields:
                [
                    ["name": "destination", "displayName": "Destination", "fieldType": "text"],
                    ["name": "username", "displayName": "Username", "fieldType": "text"],
                    ["name": "password", "displayName": "Password", "fieldType": "text"],
                    ["name": "appkey", "displayName": "App-Key", "fieldType": "text"]
                ],
            screens:
                [
                    [
                        header: "Enter your Pingdom Credentials",
                        fields: ["destination", "username", "password", "appkey"],
                        submit: "auth"
                    ]
                ]
        ]
    }

}