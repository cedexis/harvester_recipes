import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat
import org.scribe.builder.ServiceBuilder
import org.scribe.builder.api.DefaultApi10a
import org.scribe.model.OAuthRequest
import org.scribe.model.Response
import org.scribe.model.Token
import org.scribe.model.Verb
import org.scribe.oauth.OAuthService

@Grapes([
@Grab(group='org.scribe', module='scribe', version='1.3.5'),
@Grab(group = 'joda-time', module = 'joda-time', version = '2.3')
])
class MaxCDN {

    def run(config) {
        new JsonBuilder([
                usage: [
                        unit: "GB",
                        value: get_usage(config)
                ],
                bandwidth: [
                        unit: "Mbps",
                        value: get_bandwidth(config)
                ]
        ]).toPrettyString()
    }

    def get_usage(config) {
        OAuthService service = new ServiceBuilder()
                .provider(Dummy.class)
                .apiKey(config.consumer_key)
                .apiSecret(config.consumer_secret)
                .build();

        OAuthRequest request = new OAuthRequest(Verb.GET, "https://rws.netdna.com/$config.company_alias/reports/stats.json");

        Token accessToken = new Token("", "");
        service.signRequest(accessToken, request);

        Response response = request.send();

        if (response.code >= 200 && response.code < 400) {
            def data = new JsonSlurper().parseText(response.body)
            return String.format("%.2f", (data.data.stats.size as Long) / 1073741824)
        } else {
            throw new RuntimeException("Unable to pull usage data: $response.body")
        }
    }

    def get_bandwidth(config) {
        OAuthService service = new ServiceBuilder()
                .provider(Dummy.class)
                .apiKey(config.consumer_key)
                .apiSecret(config.consumer_secret)
                .build();

        def start  = DateTime.now(DateTimeZone.UTC).minusDays(1).toString(DateTimeFormat.forPattern("YYYY-MM-dd"))
        def end  = DateTime.now(DateTimeZone.UTC).toString(DateTimeFormat.forPattern("YYYY-MM-dd"))

        OAuthRequest request = new OAuthRequest(Verb.GET,
                "https://rws.netdna.com/$config.company_alias/reports/stats.json/hourly?date_from=$start&date_to=$end");

        Token accessToken = new Token("", "");
        service.signRequest(accessToken, request);

        Response response = request.send();

        if (response.code >= 200 && response.code < 400) {
            def data = new JsonSlurper().parseText(response.body)
            return String.format("%.2f", ((data.data.stats.last().size as Long) / (60*60)) / 1048576)
        } else {
            throw new RuntimeException("Unable to pull usage data: $response.body")
        }
    }

    def auth(config) {
        get_usage(config)
    }

    def recipe_config() {
        [
                name: "MaxCDN",
                description: "Bandwidth and usage metrics",
                identifier: "x.company_alias",
                run_every: 3600 * 24,
                feed_types: ["usage", "bandwidth"],
                fields:
                        [
                                ["name": "consumer_key", "displayName": "Consumer Key", "fieldType": "text", "i18n":"consumerKey"],
                                ["name": "consumer_secret", "displayName": "Consumer Secret", "fieldType": "text", "i18n":"consumerSecret"],
                                ["name": "company_alias", "displayName": "Company Alias", "fieldType": "text", "i18n":"companyAlias"]
                        ],
                screens:
                        [
                                [
                                        header: "Enter your MaxCDN Credentials",
                                        fields: ["consumer_key", "consumer_secret", "company_alias"],
                                        submit: "auth"
                                ]
                        ]
        ]
    }


}

class Dummy extends DefaultApi10a {

    @Override
    String getRequestTokenEndpoint() {
        return null
    }

    @Override
    String getAccessTokenEndpoint() {
        return null
    }

    @Override
    String getAuthorizationUrl(Token token) {
        return null
    }
}