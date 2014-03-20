import com.mashape.unirest.http.Unirest
import com.mashape.unirest.http.utils.Base64Coder
import groovy.json.JsonBuilder
import org.apache.http.HttpHeaders
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat

/**
 * Created by ben on 1/26/14.
 */
@Grapes([
@Grab(group = 'commons-io', module = 'commons-io', version = '2.4'),
@Grab(group = 'joda-time', module = 'joda-time', version = '2.3'),
@Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3'),
])
class Highwinds {

    def config = [:]
    def HIGHWINDS_API_HOST = "https://striketracker2.highwinds.com/webservices"

    def run(config) {
        this.config = config

        new JsonBuilder([
                bandwidth: [
                        unit: "Mbps",
                        value: String.format("%.2f", loadBandwidth())
                ],
                usage: [
                        unit: "GB",
                        value: String.format("%.2f", loadUsage())
                ]
        ]).toPrettyString()
    }

    def Map<String, String> headers() {
        def headers = [:]
        headers.put(HttpHeaders.CONTENT_TYPE, "application/xml");
        headers.put(HttpHeaders.ACCEPT, "application/xml");
        headers.put(HttpHeaders.ACCEPT_ENCODING, "gzip");
        final String authorization = Base64Coder.encodeString("$config.username:$config.password")
        headers.put(HttpHeaders.AUTHORIZATION, "Basic " + authorization);
        headers
    }

    def getHosts() {
        def paramsMap = [:]
        def hosts = Unirest.get("$HIGHWINDS_API_HOST/virtualhosts/").fields(paramsMap).headers(headers()).asString()
        def parsed = new XmlSlurper().parseText(hosts.body.stripMargin())

        def vhosts = []
        parsed.virtualHost.each { vhost ->
            vhosts << vhost.'*'.collectEntries {
                [(it.name() as String): it.text()]
            }
        }

        println vhosts

        vhosts
    }

    def auth(config) {
        this.config = config
        def hosts = Unirest.get("$HIGHWINDS_API_HOST/virtualhosts/").headers(headers()).asString()
        if (hosts.code != 200) {
            throw new RuntimeException("Invalid Credentials")
        }
        hosts
    }

    def loadBandwidth() {
        def bandwidthBuilder = []
        def params = [:]

        getHosts().each { host ->

            params.put("interval", "5minute");
            params.put("metrics", "actualTransfer");
            params.put("startDate", DateTime.now(DateTimeZone.UTC).minusDays(1).toString(DateTimeFormat.forPattern("YYYY-MM-dd")));
            params.put("endDate", DateTime.now(DateTimeZone.UTC).plusDays(1).toString(DateTimeFormat.forPattern("YYYY-MM-dd")));

            ["cds": "HTTP Caching", "mps": "HTTP Streaming"].each {
                def dataPoints = Unirest.get("$HIGHWINDS_API_HOST/stats/" + host.hostHash + "/" + it.key).fields(params).headers(headers()).asString()

                if (dataPoints.code == 200) {
                    def data = new XmlSlurper().parseText(dataPoints.body)

                    if (data.'*'.size() > 0) {
                        def measurements = data.'*'.collect {
                            it.actualTransfer.text() != "" ? it.actualTransfer.text() as Long : 0
                        }

                        double kb = measurements.last() // last 5 mins measurement for this day
                        double mb = kb / 1000
                        double mbits = (mb * 8);
                        double mbps = (mbits / 5) / 60;
                        bandwidthBuilder << [
                                hostHash: host.hostHash,
                                domain: host.domain,
                                location: host.location,
                                unit: "Mbps",
                                value: mbps
                        ]
                    }
                }
            }
        }

        return bandwidthBuilder.collect { it.value }.sum()
    }

    def loadUsage() {
        def usage = []
        def params = [:]

        getHosts().each { host ->

            params.put("interval", "1day");
            params.put("metrics", "actualTransfer");
            params.put("startDate", DateTime.now(DateTimeZone.UTC).withDayOfMonth(1).withMinuteOfHour(0).toString(DateTimeFormat.forPattern("YYYY-MM-dd")));
            params.put("endDate", DateTime.now(DateTimeZone.UTC).dayOfMonth().withMaximumValue().minuteOfDay().withMaximumValue().toString(DateTimeFormat.forPattern("YYYY-MM-dd")));

            ["cds": "HTTP Caching", "mps": "HTTP Streaming"].each {
                def dataPoints = Unirest.get("$HIGHWINDS_API_HOST/stats/" + host.hostHash + "/" + it.key).fields(params).headers(headers()).asString()

                if (dataPoints.code == 200) {
                    def data = new XmlSlurper().parseText(dataPoints.body)

                    if (data.'*'.size() > 0) {
                        def kbs = data.'*'.collect {
                            it.actualTransfer.text() != "" ? it.actualTransfer.text() as Long : 0
                        }.sum()

                        double gb = kbs / 1000 / 1000

                        usage << [
                                hostHash: host.hostHash,
                                domain: host.domain,
                                location: host.location,
                                unit: "GB",
                                value: gb
                        ]
                    }
                }
            }
        }

        return usage.collect { it.value }.sum()
    }

    def recipe_config() {
        [
                name: "Highwinds",
                description: "Bandwidth and usage metrics",
                identifier: "x.username",
                run_every: 3600,
                fields:
                        [
                                ["name": "username", "displayName": "Username", "fieldType": "text"],
                                ["name": "password", "displayName": "Password", "fieldType": "text"],
                        ],
                screens:
                        [
                                [
                                        header: "Enter your Credentials",
                                        fields: ["username", "password"],
                                        submit: "auth"
                                ]
                        ]
        ]
    }

}
