import com.mashape.unirest.http.Unirest
import groovy.json.JsonBuilder
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
class ChinaCache {

    def run(config) {
        new JsonBuilder(get_bandwidth(config)).toPrettyString()
    }

    def get_bandwidth(config) {

        [bandwidth:
                [
                    unit: "Mbps",
                    value: String.format("%.2f", fetch(config) )
                ]
        ]
    }

    def fetch(config) {
        def total_bandwidth = config.billing_ids.split(",").collectEntries {
            def params = [:]
            params.put("billingid", it);
            params.put("type", "monitor");
            params.put("withtime", "true");
            params.put("username", config.username);
            params.put("pass", config.password);
            params.put("starttime", DateTime.now(DateTimeZone.forTimeZone(TimeZone.getTimeZone("Asia/Shanghai"))).minusMinutes(10).toString(DateTimeFormat.forPattern("YYYYMMddHHmm")));
            params.put("endtime", DateTime.now(DateTimeZone.forTimeZone(TimeZone.getTimeZone("Asia/Shanghai"))).toString(DateTimeFormat.forPattern("YYYYMMddHHmm")));

            def response = Unirest.get("https://api.chinacache.com/reportdata/monitor/query").fields(params).asString()
            def xml = new XmlSlurper().parseText(response.body)
            def bps = xml.'**'.collect {
                if (it.name() == "Traffice") {
                    return it.text()
                }
                if( it.name() == 'DetailInfo' && it.text().contains('invalid')) {
                    throw new RuntimeException("Billing Id - $it")
                }
            }.last() as Long
            if( bps && bps > 0 ) {
                [(it as Integer): bps / 1048576]
            }else if( bps == 0) {
                [(it as Integer): 0]
            }
        }.values().sum()

        if( total_bandwidth == 0) {
            return 0.00
        }

        total_bandwidth
    }

    def auth(config) {
        def params = [:]
        params.put("username", config.username);
        params.put("pass", config.password);
        def response = Unirest.get("https://api.chinacache.com/reportdata/monitor/query").fields(params).asString()
	    if (response.body.contains("invalid userName")) {
		    throw new RuntimeException("Invalid Credentials")
	    } else {
    		return true
    	}
    }

    def recipe_config() {
        [
                name: "ChinaCache",
                description: "Bandwidth metrics",
                run_every: 60 * 5, // 5 mins
                identifier: "x.username",
                feed_types: ["bandwidth"],
                fields:
                        [
                                ["name": "username", "displayName": "Username", "fieldType": "text", "i18n":"username"],
                                ["name": "password", "displayName": "Password", "fieldType": "text", "i18n":"password", "extended_type":"password"],
                                ["name": "billing_ids", "displayName": "Billing Ids (csv)", "fieldType": "text", "i18n":"billingIds"],
                        ],
                screens:
                        [
                                [
                                        header: "Enter your ChinaCache Credentials, including a comma separated list of billing ids",
                                        fields: ["username", "password", "billing_ids"],
                                        submit: "auth"
                                ]
                        ]
        ]
    }
}
