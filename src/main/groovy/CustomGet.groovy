import com.mashape.unirest.http.Unirest
import groovy.util.logging.Slf4j

/**
 * Created by ben on 1/28/14.
 */
@Grapes([
@Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3')
])
@Slf4j
class CustomGet {

    def run(config) {
        def response = Unirest.get(config.url).asString()
        if (response.code >= 200 && response.code < 400) {
            def body = response.body
            def truncated = body.take(10237)
            return truncated
        }
        throw new RuntimeException("Unable to GET $config.url => $response.code")
    }

    def check_size(config) {
        def response = Unirest.get(config.url).asString()
        if (response.code >= 200 && response.code < 400) {
            def body = response.body
            if (body.bytes.length >= 10237) {
                throw new RuntimeException("Content on $config.url exceeds 10KB")
            }
            return true
        }
        throw new RuntimeException("Unable to GET $config.url => $response.code")
    }

    def recipe_config() {
        [
                name: "HTTP GET",
                description: "HTTP GET, body must be < 10KB",
                run_every: 3600,
                identifier: "x.url",
                fields:
                        [
                                ["name": "url", "displayName": "URL", "fieldType": "text", "i18n":"URL"],
                        ],
                screens:
                        [
                                [
                                        header: "Enter the URL that you wish to pull content from",
                                        fields: ["url"],
                                        submit: "check_size"
                                ]
                        ]
        ]
    }
}
