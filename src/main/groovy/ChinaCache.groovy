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

    def config = [:]

    def run(config) {
        this.config = config
        new JsonBuilder(get_bandwidth()).toPrettyString()
    }

    def get_bandwidth() {

        [bandwidth:
                [
                        unit: "Mbps",
                        value: String.format("%.2f", config.billing_ids.split(",").collectEntries {
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
                            }.last() as Long

                            [(it as Integer): bps / 1048576]
                        }.values().sum())
                ]
        ]
    }

    def recipe_config() {
        [
                name: "ChinaCache",
                description: "Bandwidth metrics",
                run_every: 60 * 5, // 5 mins
                identifier: "x.username",
                fields:
                        [
                                ["name": "username", "displayName": "Username", "fieldType": "text"],
                                ["name": "password", "displayName": "Password", "fieldType": "text"],
                                ["name": "billing_ids", "displayName": "Billing Ids (csv)", "fieldType": "text"],
                        ],
                screens:
                        [
                                [
                                        header: "Enter your ChinaCache Credentials, including a comma separated list of billing ids",
                                        fields: ["username", "password", "billing_ids"]
                                ]
                        ]
        ]
    }
}
