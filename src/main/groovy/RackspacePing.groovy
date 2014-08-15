import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.apache.commons.io.IOUtils
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.utils.URIBuilder
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder

@Grapes([
        @Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3')
])
/**
 *
 * RackspacePing recipe allows the customer to specify an availability check (ie sonar) for a desired host.
 *
 * The availability check is created as a RS webhook remote.ping check with a specific alarm.
 * The RS webhook alarm is callback to a fusion endpoint when a condition defined in the alarm is triggered.
 *
 * Important to note is that the recipe is called based on the user's desired frequency (ie every minute, hour, etc).
 * The customer's remote ping configuration is only created once.  When the recipe fires a second time, there is nothing
 * to do as the webhook callback will service the data feed to OM.
 *
 * The flow of this recipe is:
 * - create a RS remote.ping check for a user specified URL (this is a server availability check)
 * - create a cedexis notification for the desired fusion callback URL
 * - create a cedexis notification_plan that specifies the CRITICAL, WARNING or OK callback definition
 * - create a customer specific Alarm using the RS criteria language to check the ping response availability percentage
 * - return the data_point json to save in redis
 *
 * It makes a total of 7 Rackspace calls, 4 POSTs and 3 GETs on a first time run.  The notification and
 * notification plan are not customer specific so once they are created, no need to create again.
 *
 * @author grillo
 * @since August, 2014
 *
 */
class RackspacePing {

    def final API_AUTH_URL_V2 = "https://identity.api.rackspacecloud.com/v2.0/tokens"

    def final ping_check_type = 'remote.ping'

    def final entity_ping_check_parent = 'fusion'

    def final notifications_label = 'Fusion Remote Ping Webhook Notifications'

    def final notification_plan_label = 'Fusion Remote Ping Notification Plan'

    def final fusion_webhook_uri = '/rest/webhook/alarm/rackspace'

    def run(config) {

        def raxServices = getCedexisRaxServices(config)

        def fusion_data_point = configurePingCheck(config, raxServices)

        new JsonBuilder(fusion_data_point).toPrettyString()
    }

    // Get the auth token and service catalog for subsequent Rackspace calls
    def getCedexisRaxServices(config) {
        def raxServices = [:]
        // auth to rackspace with these creds
        HttpPost request = new HttpPost(API_AUTH_URL_V2);

        def authRequest = [
                "auth": [
                        "RAX-KSKEY:apiKeyCredentials": [
                                "username": config.system_account_user,
                                "apiKey": config.system_account_api_key
                        ]
                ]
        ]

        StringEntity requestEntity = new StringEntity(new JsonBuilder(authRequest).toPrettyString(), "application/json", "UTF-8")
        request.setEntity(requestEntity)
        HttpResponse response = HttpClientBuilder.create().build().execute(request)
        int statusCode = response.getStatusLine().getStatusCode();

        def content = IOUtils.toString(response.getEntity().getContent())

        switch (statusCode) {
            case 200:
                def auth = new JsonSlurper().parseText(content)
                raxServices.put("token",auth.access.token.id)
                raxServices.put("serviceCatalog", auth.access.serviceCatalog)

                break;
            default:
                throw new Exception("Authentication failed: $response.statusLine.reasonPhrase")
        }

        raxServices

    }

    def configurePingCheck(config, customer_rax_services) {

        def serviceCatalog = customer_rax_services["serviceCatalog"]
        def token = customer_rax_services["token"]

        assert serviceCatalog && token

        def result = [:]
        def cloudMonitoring

        if (serviceCatalog.find { it.name == "cloudMonitoring" }) {
            cloudMonitoring = serviceCatalog.find { it -> it.name == "cloudMonitoring" }.endpoints
        }else {
            println "No Rackspace cloud monitoring endpoints found for username: ${config.username}"
            return result;
        }

        def api_endpoint = cloudMonitoring[0].publicURL

        // Get cedexis Rackspace account entities, customer ping checks use fusion entity as parent
        def fusion_entity = fetch(api_endpoint, "/entities", token)
                .values.find{ it.label == entity_ping_check_parent }

        def checks = fetch(api_endpoint, "/entities/$fusion_entity.id/checks/", token)
        def ping_checks = filterChecksByType(checks, ping_check_type)

        def installed_ping_check = filterChecksByInstallId(config, ping_checks)

        if( ! installed_ping_check ) {
            if(testNewPingCheck(config, fusion_entity, api_endpoint, token)) {

                def check_id =  createPingCheck(config, fusion_entity, api_endpoint, token)

                def notification_plan_id = createWebHooksNotificationPlan( api_endpoint, token, config)

                createCustomerWebHookAlarm( fusion_entity, config, check_id, notification_plan_id, api_endpoint, token)
            }
        }

        return [(config.hostname):["unit":"boolean","value":true]]
    }

    def testNewPingCheck(config, fusion_entity, api_endpoint, token){

        def body = [
                "monitoring_zones_poll":["mzdfw","mzlon","mzord"],
                "type":"remote.ping",
                "target_alias":null,
                "target_hostname":"$config.hostname",
                "timeout":config.timeout.toInteger(),
        ]

        def response_headers = post( api_endpoint, "/entities/$fusion_entity.id/test-check", body, token )
        def header = response_headers.find{ it.name == 'X-RateLimit-Type'}
        return header.value == 'test_check'

    }

    def filterChecksByType(checks, target_type) {
        def ping_checks = []

        checks.values.each{ check ->
            if( check.type == target_type) {
                ping_checks << check
            }
        }

        ping_checks
    }

    def filterChecksByInstallId(config, ping_checks) {
        return  ping_checks.find{
            isMetaDataExists(it.metadata) && it.metadata.zone_id == config.cdx_zone_id && it.metadata.customer_id == config.cdx_customer_id && it.metadata.install_id == config.install_id
        }
    }

    def isMetaDataExists(metadata) {
        if(metadata && metadata.zone_id && metadata.customer_id && metadata.install_id) {
            return true
        }
        false
    }

    def createPingCheck(config, fusion_entity, api_endpoint, token) {

        // Rackspace API max period (30 .. 1800) seconds
        def period = config.run_every.toInteger()
        if( period > 1800) {
            period = 1800
        }

        def body = [
                "label":"Customer $config.cdx_customer_id Remote Ping",
                "monitoring_zones_poll":["mzdfw","mzlon","mzord"],
                "type":"remote.ping",
                "target_alias":null,
                "target_hostname":"$config.hostname",
                "target_resolver":null,
                "timeout":config.timeout.toInteger(),
                "metadata":["zone_id":"$config.cdx_zone_id","customer_id":"$config.cdx_customer_id","install_id":"$config.install_id"],
                "period":period
        ]

        println "Create new ping check with request body:\n $body"

        validatePostAndReturnEntityId(
                post( api_endpoint, "/entities/$fusion_entity.id/checks", body, token ),
                "Expected Response Header X-Object-ID for /entities/$fusion_entity.id/checks")
    }

    def createWebHooksNotificationPlan( api_endpoint, token, config) {

        // the webhook endpoint must be internet accessible as the alarm request is a Rackspace webhook callback
        // if running locally, use external IP address as harvester server endpoint (may require local/office firewall/wireless router change)
        def webhook_url_base = "http://${getFusionHost(config)}:8089".toString()

        println "Webhook URL base: $webhook_url_base"

        def notifications = getCedexisNotifications(api_endpoint, token)

        def notification = notifications.find{ it.type == 'webhook' && it.label == notifications_label && it.details.url.contains(webhook_url_base)}

        // create a new notification (webhook endpoint) and notification_plan (allowable response states)
        if( ! notification) {
            def body = [
                    "label"  : "$notifications_label",
                    "type"   : "webhook",
                    "details": [
                            "url": "$webhook_url_base$fusion_webhook_uri"
                    ]
            ]
            def notification_id = validatePostAndReturnEntityId(
                    post(api_endpoint, '/notifications', body, token),
                    "Expected Response Header X-Object-ID for /notifications")

            body = [
                    "label": "$notification_plan_label",
                    "warning_state": [
                            "$notification_id"
                    ],
                    "critical_state": [
                            "$notification_id"
                    ],
                    "ok_state": [
                            "$notification_id"
                    ]
            ]
            def notification_plan_id =  validatePostAndReturnEntityId(
                    post(api_endpoint, '/notification_plans', body, token),
                    "Expected Response Header X-Object-ID for /notification_plans")

            return notification_plan_id
        }

        getWebhookNotificationPlanId(api_endpoint, token)

    }

    def getWebhookNotificationPlanId(api_endpoint, token) {

        def notification_plans = fetch(api_endpoint, '/notification_plans', [:], token)

        def notification_plan =  notification_plans.values.find{ it.label == "$notification_plan_label"}

        checkNotNull(notification_plan, "Expected $notification_plan_label for /notification_plans")

        notification_plan.id

    }

    def createCustomerWebHookAlarm( fusion_entity, config, check_id, notification_plan_id, api_endpoint, token) {

        def body = [
            "label":"Customer $config.cdx_customer_id Ping Check Alarm",
            "check_id": "$check_id",
            "criteria": "if (metric['available'] < 80) {\n  return new AlarmStatus(CRITICAL, 'Packet loss is greater than 20%');\n}\n\nif (metric['available'] < 95) {\n  return new AlarmStatus(WARNING, 'Packet loss is greater than 5%');\n}\n\nreturn new AlarmStatus(OK, 'Packet loss is normal');",
            "notification_plan_id": "$notification_plan_id",
            "disabled":false,
            "metadata":["zone_id":"$config.cdx_zone_id","customer_id":"$config.cdx_customer_id","install_id":"$config.install_id"]
        ]

        return  validatePostAndReturnEntityId(
                post(api_endpoint, "/entities/$fusion_entity.id/alarms", body, token),
                "Expected Response Header X-Object-ID for /entities/$fusion_entity.id/alarms")
    }

    def getCedexisNotifications(api_endpoint, token ) {
        def response = fetch(api_endpoint, '/notifications', [:], token)
        def notifications = []

        if( response && response.values) {
            notifications = response.values
        }

        notifications
    }

    def validatePostAndReturnEntityId( response_headers, errMsg) {
        def id = response_headers.find{ it.name == 'X-Object-ID'}
        checkNotNull(id, "System Error: $errMsg")
        return id.value
    }

    def post(endpoint, uri_base, params, token) {
        HttpPost request = new HttpPost()
        request.addHeader("X-Auth-Token", token as String)
        URIBuilder uri = new URIBuilder(new URI("$endpoint$uri_base"))

        if (params) {
            request.setEntity(new StringEntity(new JsonBuilder(params).toPrettyString(), ContentType.APPLICATION_JSON));
            println(new JsonBuilder(params).toPrettyString())
        }

        request.setURI(uri.build())
        HttpResponse res = HttpClientBuilder.create().build().execute(request)

        println("[$res.statusLine.statusCode] => POST $endpoint$uri_base $params")

        if( res && (res.statusLine.statusCode as String).startsWith("2")) {
            return res.getAllHeaders()
        }

        throw new RuntimeException("There was a problem with creating your Rackspace ping check, please try again later.")
    }

    def fetch(endpoint, uri_base, params = [:], token) {

        HttpGet request = new HttpGet()
        request.addHeader("X-Auth-Token", token as String)
        URIBuilder uri = new URIBuilder(new URI("$endpoint$uri_base"))
        if (params) {
            params.entrySet().each { param ->
                uri.addParameter(param.key as String, param.value as String)
            }
        }
        request.setURI(uri.build())
        HttpResponse res = HttpClientBuilder.create().build().execute(request)

        println("[$res.statusLine.statusCode] => GET $endpoint$uri_base $params")

        if (res && (res.statusLine.statusCode as String).startsWith("2")) {
            def content = IOUtils.toString(res.entity.content)
            return new JsonSlurper().parseText(content)
        }

        throw new Exception(res.statusLine.reasonPhrase)

    }

    def checkNotNull(value, errMsg) {
        if(! value) {
            throw new RuntimeException(errMsg)
        }
    }

    def getFusionHost(config) {
        // TODO check config for fusion hostname
        // if  not running in dev or prod, use local IP address for harvester server endpoint
        return getLocalExternalIp()
    }

    def getLocalExternalIp() {
        HttpGet request = new HttpGet()
        // request.setHeader("Content-Encoding", "gzip, deflate");
        request.setHeader("User-Agent", "curl/7.30.0");
        URIBuilder uri = new URIBuilder(new URI("http://ifconfig.me"))
        request.setURI(uri.build())
        HttpResponse response = HttpClientBuilder.create().build().execute(request)
        return IOUtils.toString(response.entity.content).replaceAll("\\s+","")
    }

    def validate(config) {

        if(! isValidHost(config)) {
            throw new RuntimeException("Invalid host/hostname")
        }

        if(! isValidTimeout(config)) {
            throw new RuntimeException("Invalid timeout")
        }
        true
    }

    def isValidHost(config) {
        def valid = true;
        try {
            if(! config.hostname) {
                return false;
            }

            if (config.hostname.contains(":")) {
                return false;
            }
            InetAddress.getByName(config.hostname)
        }catch(Exception e) {
            valid = false;
        }
        valid
    }

    def isValidTimeout(config) {
        def valid = true
        try {
            def seconds = Integer.parseInt(config.timeout)
            if( seconds == 0) {
                valid = false;
            }
        }catch(Exception e) {
            valid =  false
        }
        valid
    }

    def recipe_config() {
        [
                name: "Rackspace Ping",
                description: "Ping Availability Check",
                run_every: 60,
                identifier: "x.hostname",
                no_platform: true,
                use_system_account: true,
                system_account: [
                    "user_key":"rax_username",
                    "api_key":"rax_api_key"
                ],
                fields:
                        [
                                ["name": "hostname", "displayName": "Hostname", "fieldType": "text"],
                                ["name": "timeout", "displayName": "Timeout (seconds)", "fieldType": "text"]
                        ],
                screens:
                        [
                                [
                                        header: "Rackspace Availability Ping Check",
                                        fields: ["hostname", "timeout"],
                                        submit: "validate"
                                ]
                        ]
        ]
    }

}
