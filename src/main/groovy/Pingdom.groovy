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
	    def user = config['username']
        def password = config['password']
        def appkey = config['appkey']
        def destination = config['destination']

	    //get the currents checks
	    def response = Unirest.get(REST_URL)
	        .header("App-Key", appkey)
	        .basicAuth(user, password).asString()

	    // Parse the response
	    def checks_result = new JsonSlurper().parseText(response.body)
	    def exists = false
	    checks_result.checks.each { it ->
		    if (it.name == "fusion check" && it.hostname == destination) {
			    exists = true
		    }
	    }

	    if (!exists) {
		    //create the check
		    response = Unirest.post(REST_URL)
			    .header("App-Key", appkey)
			    .field("name", "fusion check")
			    .field("type", "http")
			    .field("host", destination)
			    .basicAuth(user, password).asString()
	    }

	    def result = [:]
	    result["is_alive"] = ["value": "true", "unit": "boolean", "bypass_data_points":"true"]
	    new JsonBuilder(result).toPrettyString()
    }

    def auth(config) {
        def user = config['username']
        def password = config['password']
        def appkey = config['appkey']
        try {
            def response = Unirest.get(REST_URL)
                .header("App-Key", appkey)
                .basicAuth(user, password).asString()
	    
            if (response.code != 200) {
                throw new RuntimeException("Invalid Credentials")
            }
	        return true
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage())
        }
    }

    def recipe_config() {
        [
                name: "Pingdom",
                description: "Server availability check",
                identifier: "x.destination",
                run_every: 60,
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
