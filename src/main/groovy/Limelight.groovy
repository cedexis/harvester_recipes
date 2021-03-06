import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.apache.commons.codec.DecoderException
import org.apache.commons.codec.binary.Hex
import org.apache.commons.io.IOUtils
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.utils.URIBuilder
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException

@Grapes([
        @Grab(group = 'commons-codec', module = 'commons-codec', version = '1.9'),
        @Grab(group = 'org.apache.httpcomponents', module = 'httpclient', version = '4.3.2'),
        @Grab(group='commons-io', module='commons-io', version='2.4'),
        @Grab(group='joda-time', module='joda-time', version='2.3')

])
@Slf4j
class Limelight {

    def REST_URL = 'https://control.llnw.com/traffic-reporting-api/v2/usage'

    def SERVICES = ['hls','mss','hds','flash','dynamicorigin']

    def run(config) {
        try {

            def usage = usageData(config).collectEntries { [(it.key as String): it.value.total.value as BigDecimal] }.values().sum()

            // we are dividing by 1000 instead of 1024 because this is what matches the GB Transferred number in the LLNW portal
            return new JsonBuilder(['usage': [
                    unit: "GB",
                    value: String.format("%.2f", usage / 1000)
            ]]).toPrettyString()
        } catch (Exception e) {
            return e.getMessage()
        }
    }

    def usageData(config) {
        def id = config['username']
        def shortname = config['shortname']?config['shortname'] : id
        def secret = config['api_shared_key']

        def result = [:]
        def addServices = ''

        if(config['services']) {
            addServices = ",${config['services']}"
        }

        def params = [shortname: shortname, service: "http,https${addServices}" as String, reportDuration: 'month', sampleSize: 'daily']
        def responses = getReport(id, secret, REST_URL, params);

        responses.responseItems.each { response ->

            def measures = response['measure']
            println JsonOutput.toJson(measures)
            //calculate the total values for each measure
            def total = measures.inject([requests: 0, connections: 0, totalbits: 0, inbits: 0, outbits: 0]) { total, current ->
                total.each { key, value ->
                    total[key] += current[key] * 86400 //seconds in a day
                }
                total
            }
            def value = [:]
            value['requests'] = [value: Math.round(total['requests']), unit: 'requests']
            value['connections'] = [value: Math.round(total['connections']), unit: 'connections']
            value['total'] = [value: total['totalbits'] / 8000000, unit: 'MegaBytes'] //this is the way the conversion is done in limelight dashboard
            value['ingress'] = [value: total['inbits'] / 8000000, unit: 'MegaBytes']
            value['egress'] = [value: total['outbits'] / 8000000, unit: 'MegaBytes']
            result[response['service']] = value

        }

        return result
    }

    def bandwidthData(id, secret) {
        def result = [:]
        def startDate = DateTime.now().toString(DateTimeFormat.forPattern("YYYY-MM-dd"))
        def endDate = DateTime.now().plusDays(1).toString(DateTimeFormat.forPattern("YYYY-MM-dd"))
        def params = [shortname: id, service: 'http,https,mss', reportDuration: 'custom', sampleSize: 'hourly', startDate: startDate, endDate: endDate, doNotEraseHours: true]
        def responses = getReport(id, secret, REST_URL, params);

        responses['responseItems'].each { response ->
            def measures = response['measure']
            def reqs_per_sec = 0
            def conn_per_sec = 0
            def total_bits_per_sec = 0
            def inbits_per_sec = 0
            def outbits_per_sec = 0
            if (!measures.isEmpty()) {
                def lastMeasure = measures.get(measures.size() - 1)
                reqs_per_sec = lastMeasure['requests']
                conn_per_sec = lastMeasure['connections']
                total_bits_per_sec = lastMeasure['totalbits'] / 1000000
                //this is the way the conversion is done in limelight dashboard
                inbits_per_sec = lastMeasure['inbits'] / 1000000
                outbits_per_sec = lastMeasure['outbits'] / 1000000
            }
            def value = [:]
            value['reqs_per_sec'] = [value: reqs_per_sec, unit: 'rps']
            value['conn_per_sec'] = [value: conn_per_sec, unit: 'cps']
            value['total_bits_per_sec'] = [value: total_bits_per_sec, unit: 'Mbps']
            value['inbits_per_sec'] = [value: inbits_per_sec, unit: 'Mbps']
            value['outbits_per_sec'] = [value: outbits_per_sec, unit: 'Mbps']
            result[response['service']] = value
        }
        return result
    }

    def getReport(id, secret, url, params = [:]) {
        Long timestamp = System.currentTimeMillis();
        HttpGet request = new HttpGet(url as String)
        URIBuilder uri = new URIBuilder(new URI(url as String))
        if (params) {
            params.entrySet().each { param ->
                uri.addParameter(param.key as String, param.value as String)
            }
        }
        request.setURI(uri.build())
        request.setHeader("Content-Type", "application/json")
        request.setHeader("X-LLNW-Security-Principal", id)
        request.setHeader("X-LLNW-Security-Timestamp", timestamp.toString())
        def hmac = generateMac("GET", uri.build().toString(), null, secret, timestamp)
        request.setHeader("X-LLNW-Security-Token", hmac)

        log.debug("fetching $url $params")

        HttpResponse response = http_client().execute(request)
        if (response) {

            log.debug("${response.getStatusLine()}")
            def content = IOUtils.toString(response.getEntity().getContent())
            switch (response.getStatusLine().statusCode) {
                case 200:
                    def data = new JsonSlurper().parseText(content)
                    return data
                    break;
                default:
                    throw new Exception("Error while trying to connect to Limelight servers")
            }
        }
        null
    }

    def generateMac(httpMethod, String url, body, secret, timestamp) throws NoSuchAlgorithmException, InvalidKeyException, IllegalStateException, UnsupportedEncodingException, DecoderException {
        def decodedSharedKey = Hex.decodeHex(secret.toCharArray());
        def dataString = httpMethod.toUpperCase() + url.replaceFirst('\\?', '') + timestamp;

        if (body != null) {
            dataString = dataString + body;
        }
        SecretKeySpec keySpec = new SecretKeySpec(decodedSharedKey, "HmacSHA256");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.reset();
        mac.init(keySpec);
        byte[] bytes = mac.doFinal(dataString.getBytes("UTF-8"));
        Formatter formatter = new Formatter()
        bytes.each { b ->
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }

    def http_client() {
        return HttpClientBuilder.create().build()
    }

    def auth(config) {
        def responses
        try {
            def id = config['username']
            def shortname = config['shortname'] ? config['shortname'] : id
            def secret = config['api_shared_key']

            if (!validServices(config)) {
                throw new IllegalArgumentException("Invalid additional services, valid additional services are ${SERVICES}")
            }
            // we are only using http for the service because we are just validating creds, we don't use the metrics
            def params = [shortname: shortname, service: 'http', reportDuration: 'month', sampleSize: 'daily']
            responses = getReport(id, secret, REST_URL, params);

        }catch(IllegalArgumentException e) {
            throw new RuntimeException(e.getMessage())
        } catch (Exception e) {
            throw new RuntimeException("Invalid Credentials")
        }

        responses != null
    }

    def validServices(config) {
        def valid = true
        if( config['services']) {
            def services = config['services'].replaceAll("\\s+", "")
            services.split(',').each { service ->
                if (!SERVICES.contains(service)) {
                    valid = false
                }
                // we have these services by default, don't let the user add them again or API call will fail
                if(service == 'http' || service == 'https') {
                    valid = false
                }
            }
            if (valid) {
                config['services'] = services
            }
        }
        return valid
    }


    def recipe_config() {
        [
                name: "Limelight",
                description: "Usage metrics",
                identifier: "x.username",
                run_every: 3600,
                feed_types: ["usage"],
                fields:
                        [
                                ["name": "username", "displayName": "API Username", "fieldType": "text", "i18n":"apiUsername"],
                                ["name": "api_shared_key", "displayName": "API Shared Key", "fieldType": "text", "i18n":"apiSharedKey", "extended_type":"password"],
                                ["name": "shortname", "displayName": "CDN Account Shortname", "fieldType": "text", "i18n":"accountShortname"],
                                ["name": "services", "displayName": "Additonal Services (Optional) - Add 'hls','mss','hds','flash' or 'dynamicorigin' to http/https", "fieldType": "text", "optional" : "true", "i18n":"additionalServices"]
                        ],
                screens:
                        [
                                [
                                        header: "Enter your Limelight Credentials",
                                        fields: ["username", "api_shared_key", "shortname", "services"],
                                        submit: "auth"
                                ]
                        ]
        ]
    }

}
