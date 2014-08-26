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
        def destination = config['destination']
        def appkey = config['appkey']
        try {
            def response = Unirest.post(REST_URL)
                .header("App-Key", appkey)
                .field("name", "fusion check")
                .field("type", "http")
                .field("host", destination)
                .basicAuth(user, password).asString()
		
        } catch (Exception e) {
            return e.getMessage()
        }
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
                                    header: "Enter ...",
                                    fields: ["destination", "username", "password", "appkey"],
                                    submit: "auth"
                            ]
                        ]
        ]

    }

}