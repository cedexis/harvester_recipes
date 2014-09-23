@Grapes([
        @Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3'),
        @Grab(group='joda-time', module='joda-time', version='2.3')
])

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.apache.commons.io.IOUtils
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpDelete
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.utils.URIBuilder
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat

class RackspaceRemoteHttp {
    def final API_AUTH_URL_V2 = "https://identity.api.rackspacecloud.com/v2.0/tokens"

    def final remote_check_type = 'remote.http'

    def final fusion_entity_parent = 'fusion'

    def final notifications_label = 'Fusion Webhook Notifications'

    def final notification_plan_label = 'Fusion Remote Http Notification Plan'

    def final fusion_webhook_uri = '/rest/webhook/rackspace/remote/http'

    def run(config) {

        config << ["host":new URL(config.url).getHost()]

        def cedexis_rax_services = getCedexisRaxServices(config)

        def response  = configureHttpCheck(config, cedexis_rax_services)

        new JsonBuilder(response).toPrettyString()
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
    def configureHttpCheck(config, cedexis_rax_services) {

        def api_endpoint = getApiEndpoint(cedexis_rax_services)
        def token = getApiToken(cedexis_rax_services)

        // Get cedexis Rackspace account entities, customer http checks use fusion entity as parent
        def fusion_entity = fetch(api_endpoint, "/entities", token)
                .values.find{ it.label == fusion_entity_parent }

        def checks = fetch(api_endpoint, "/entities/$fusion_entity.id/checks/", token)
        def http_checks = filterChecksByType(checks, remote_check_type)

        def installed_http_check = filterChecksByInstallId(config, http_checks)

        if( ! installed_http_check ) {
            if(testNewHttpCheck(config, fusion_entity, api_endpoint, token)) {

                def check_id =  createHttpCheck(config, fusion_entity, api_endpoint, token)

                def notification_plan_id = createWebHooksNotificationPlan( api_endpoint, token, config)

                createCustomerWebHookAlarm( fusion_entity, config, check_id, notification_plan_id, api_endpoint, token)

                def response =  getInstalledCheck(fusion_entity, check_id, api_endpoint, token)

                response << ['bypass_data_points':true,
                             'server_status':'UP',
                             'last_status_check': DateTime.now(DateTimeZone.forTimeZone(TimeZone.getTimeZone("UTC"))).toString(DateTimeFormat.forPattern("YYYY-MM-dd 'AT' HH:mm 'GMT'"))]

                return response
            }
        }
        // config.cdx_zone_id, config.cdx_customer_id, config.install_id
        // update_elements: first, last, all, oldest, newest
        return [
            "bypass_data_points":true,
            "update_result":true,
            "update_map":["last_status_check":DateTime.now(DateTimeZone.forTimeZone(TimeZone.getTimeZone("UTC"))).toString(DateTimeFormat.forPattern("YYYY-MM-dd 'AT' HH:mm 'GMT'"))]
        ]
    }
    def testNewHttpCheck(config, fusion_entity, api_endpoint, token){

        def body = [
                "monitoring_zones_poll":["mzord"],
                "type":"$remote_check_type",
                "details" : [
                    "url":"$config.url",
                    "method":"$config.method",
                ],
                "timeout":config.timeout.toInteger(),
                "target_hostname": "$config.host"
        ]

        def response_headers = post( api_endpoint, "/entities/$fusion_entity.id/test-check", body, token )
        def header = response_headers.find{ it.name == 'X-RateLimit-Type'}
        return header.value == 'test_check'

    }

    def getInstalledCheck( fusion_entity, check_id, api_endpoint, token ) {
        // /entities/entityId/checks/checkId
        def response =  fetch(api_endpoint, "/entities/$fusion_entity.id/checks/$check_id", [:], token )

    }

    def filterChecksByType(checks, target_type) {
        def http_checks = []

        checks.values.each{ check ->
            if( check.type == target_type) {
                http_checks << check
            }
        }

        http_checks
    }
    def filterChecksByInstallId(config, http_checks) {
        return  http_checks.find{
            isMetaDataExists(it.metadata) && it.metadata.zone_id == config.cdx_zone_id && it.metadata.customer_id == config.cdx_customer_id && it.metadata.install_id == config.install_id
        }
    }

    def isMetaDataExists(metadata) {
        if(metadata && metadata.zone_id && metadata.customer_id && metadata.install_id) {
            return true
        }
        false
    }

    def createHttpCheck(config, fusion_entity, api_endpoint, token) {

        // Rackspace API max period (30 .. 1800) seconds
        def period = config.run_every.toInteger()
        if( period > 1800) {
            period = 1800
        }

        def body = [
                "label":"Customer $config.cdx_customer_id Remote Http",
                "monitoring_zones_poll":["mzdfw","mzlon","mzord","mzhkg","mzsyd","mziad"],
                "type":"$remote_check_type",
                "timeout":config.timeout.toInteger(),
                "metadata":["zone_id":"$config.cdx_zone_id","customer_id":"$config.cdx_customer_id","install_id":"$config.install_id"],
                "period":period,
                "target_hostname": "$config.host",
                "details" : [
                        "url":"$config.url",
                        "method":"$config.method",
                ]
        ]

        println "Create new http check with request body:\n $body"

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

        notifications = deleteAllNotifications( notifications, api_endpoint, token )

        def notification = notifications.find{
                it.type == 'webhook' &&
                it.label == "$notifications_label" &&
                it.details.url.contains(webhook_url_base)
        }

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

            testNotification(notification_id, api_endpoint, token)

            body = [
                    "label": "$notification_plan_label - $webhook_url_base",
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

        // criteria for CRITICAL webhook callback:
        // if http response code 4XX or 5XX
        // if connection time > user defined timeout in seconds
        def timeout = config.timeout.intValue() * 1000
        def body = [
                "label":"Customer $config.cdx_customer_id Remote Http Alarm",
                "check_id": "$check_id",
                "criteria": "if (metric['code'] regex '4[0-9][0-9]') {\n" +
                        "  return new AlarmStatus(CRITICAL, 'HTTP server responding with 4xx status');\n" +
                        "}\n" +
                        "\n" +
                        "if (metric['code'] regex '5[0-9][0-9]') {\n" +
                        "  return new AlarmStatus(CRITICAL, 'HTTP server responding with 5xx status');\n" +
                        "}\n" +
                        "return new AlarmStatus(OK, 'HTTP server is functioning normally');",

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

        throw new RuntimeException("There was a problem with creating your Rackspace http check, please try again later.")
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

    def getApiEndpoint(customer_rax_services) {
        def serviceCatalog = customer_rax_services["serviceCatalog"]
        checkNotNull(serviceCatalog, "Missing service catalog from rax services")
        checkNotNull(serviceCatalog.find { it.name == "cloudMonitoring" },"No Rackspace cloud monitoring services found")

        def cloudMonitoring = serviceCatalog.find { it -> it.name == "cloudMonitoring" }.endpoints

        cloudMonitoring[0].publicURL
    }

    def getApiToken(customer_rax_services) {
        return customer_rax_services['token']
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

        if(! isValidUrl(config)) {
            throw new RuntimeException("Invalid URL")
        }

        if(! isValidTimeout(config)) {
            throw new RuntimeException("Invalid timeout, must be greater than 0 and less than frequency")
        }
        true
    }

    def isValidUrl(config) {
        def valid = true
        try {
            if(! config.url) {
                return false
            }

        new URL(config.url);

        }catch(Exception e) {
            valid = false
        }
        valid
    }

    def isValidTimeout(config) {
        def valid = true
        try {
            def seconds = Integer.parseInt(config.timeout)
            if( seconds == 0) {
                valid = false
            }
            def frequency = Integer.parseInt(config.run_every)
            if( seconds >= frequency) {
                return false
            }
        }catch(Exception e) {
            valid =  false
        }
        valid
    }

    def getHttpMethods(config) {
        LinkedHashMap<String,String> map = [:]

        map.put("GET", ["GET":"GET - Use HTTP Get Method"])
        map.put("HEAD", ["HEAD":"HEAD - Use HTTP Head Method"])

        map

    }

    def recipe_config() {
        [
                name: "HTTP Server Availability",
                description: "Test URL for HTTP 2XX response code",
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
                                ["name": "url", "displayName": "URL", "fieldType": "text"],
                                ["name": "timeout", "displayName": "Connection Timeout (seconds)", "fieldType": "text"],
                                ["name": "method", "displayName": "Http Method", "fieldType": "select", source: "getHttpMethods"]
                        ],
                screens:
                        [
                                [
                                        header: "Http Server Availability Check",
                                        fields: ["url", "timeout", 'method'],
                                        submit: "validate"
                                ]
                        ]
        ]
    }

    def testNotification( notification_id, api_endpoint, token) {
        // /notifications/notificationId/test

        def response = post(api_endpoint, "/notifications/${notification_id}/test", [:], token)
        println "Notification test response: $response"


    }

    // temporary just to clean things up during development
    // TODO remove after recipe is finialize
    def deleteAllNotifications(notifications, api_endpoint, token) {
        HttpDelete request = new HttpDelete()
        request.addHeader("X-Auth-Token", token as String)

        notifications.each{ notification ->
            URIBuilder uri = new URIBuilder(new URI("$api_endpoint/notifications/${notification.id}"))
            request.setURI(uri.build())
            HttpResponse res = HttpClientBuilder.create().build().execute(request)
            println("[$res.statusLine.statusCode] => DELETE $api_endpoint/notifications/${notification.id}")

        }

        def notification_plans = fetch(api_endpoint, "/notification_plans", token)

        notification_plans.values.each{ notification_plan ->
            URIBuilder uri = new URIBuilder(new URI("$api_endpoint/notification_plans/${notification_plan.id}"))
            request.setURI(uri.build())
            HttpResponse res = HttpClientBuilder.create().build().execute(request)
            println("[$res.statusLine.statusCode] => DELETE $api_endpoint/notification_plans/${notification_plan.id}")

        }

        return [:]
    }

}
