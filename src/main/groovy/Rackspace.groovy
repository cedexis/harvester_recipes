@Grapes([
        @Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3'),
        @Grab(group='joda-time', module='joda-time', version='2.3')
])

import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.apache.commons.io.IOUtils
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.utils.URIBuilder
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.joda.time.format.DateTimeFormat

class Rackspace {

    def API_AUTH_URL_V2 = "https://identity.api.rackspacecloud.com/v2.0/tokens";

    def run(config) {

        DateTimeZone.setDefault(DateTimeZone.UTC)

        def customer_rax_services = [:]

        if( ! auth(config, customer_rax_services) ) {
            throw new Exception("Authentication failed")
        }

        def server_data = payload(config, customer_rax_services)

        // get the servers with metrics and then remove from the data structure so we don't break the filter method
        def servers_with_metrics = server_data.get("servers_with_metrics")
        server_data.remove("servers_with_metrics")

        def result = filter(config, server_data)

        result.findAll {
            it.value.keySet().intersect(["load_average",
                    "cpu_usage",
                    "cpu_stolen",
                    "mem_usage",
                    "disk_usage",
                    "tx_bytes",
                    "rx_bytes",
                    "health"])
        }.collectEntries {
            it
        }

        def servers_with_agents = result.findAll {
            it.value.keySet().intersect(["load_average",
                    "cpu_usage",
                    "cpu_stolen",
                    "mem_usage",
                    "disk_usage",
                    "tx_bytes",
                    "rx_bytes",
                    "health"])
        }

        if( ! servers_with_agents || servers_with_agents.size() == 0) {
            throw new Exception("The Rackspace API queries did not return results the following monitoring agents: [load_average, ping_average, cpu_usage, cpu_stolen, mem_usage, disk_usage, tx_bytes, rx_bytes]");
        }

        def units = ["load_average":"load",
                "cpu_usage":"%",
                "cpu_stolen":"%",
                "mem_usage":"%",
                "disk_usage":"%",
                "tx_bytes":"bps",
                "rx_bytes":"bps",
                "health": "0-5"]

        def formats = ["load_average":"%s",
                "cpu_usage":"%.2f",
                "cpu_stolen":"%.2f",
                "mem_usage":"%.2f",
                "disk_usage":"%.2f",
                "tx_bytes":"%s",
                "rx_bytes":"%s",
                "health": "%s"]

        def all_metrics = servers_with_agents.collect {
            def label = it.value.label.replaceAll("\\.","_")

            it.value.subMap(["load_average",
                    "cpu_usage",
                    "cpu_stolen",
                    "mem_usage",
                    "disk_usage",
                    "tx_bytes",
                    "rx_bytes",
                    "health"]).collectEntries { key,value ->
                        if( value ) {
                            ["${label}_${key}": [
                                    "unit" : units.get(key),
                                    "value": String.format(formats.get(key) as String, value)
                                ]
                            ]
                        }else {
                            [:]
                        }
            }
        }.sum()

        checkForMissingServerHealth(all_metrics, servers_with_metrics)

        def health_metrics = all_metrics.findAll { it.key.contains('_health') }

        new JsonBuilder(health_metrics).toPrettyString()
    }

    // the server health score is the main metric used in OM apps, raise a failed condition so
    // WorkerActor.handle_push() does not send payload to OM app
    def checkForMissingServerHealth(metrics, servers_with_metrics) {

        println "Testing servers $servers_with_metrics for health metric"

        servers_with_metrics.each {
            if( ! metrics.containsKey("${it.replaceAll("\\.","_")}_health") ) {
                metrics.put("recipe_fail_do_not_push","Rackspace API failed, unable to determine health for server $it")
            }
        }
    }

    def auth(creds) {
        return auth(creds, [:])
    }

    def auth(creds, customer_rax_services) {
        // auth to rackspace with these creds
        HttpPost request = new HttpPost(API_AUTH_URL_V2);

        def authRequest = [
                "auth": [
                        "RAX-KSKEY:apiKeyCredentials": [
                                "username": creds.username,
                                "apiKey": creds.api_key
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

                customer_rax_services.put("token",auth.access.token.id)
                customer_rax_services.put("serviceCatalog", auth.access.serviceCatalog)

                if(! checkUserRoles(auth)) {
                    throw new Exception("Authorization failed: Rackspace user ${auth.access.user.name} does not have the cloud monitoring role enabled")
                }

                break;
            default:
                throw new Exception("Authentication failed: $response.statusLine.reasonPhrase")
        }

        customer_rax_services.size() == 2

        // token != null && serviceCatalog != null
    }

    def checkUserRoles( def auth ) {
        def roles = auth.access.user.roles

        def authorized = false
        roles.each{ it ->
            if( it.name.contains("monitoring") || it.name.contains("user-admin") ) {
                authorized = true
            }
        }
        return authorized;
    }

    def payload(config, customer_rax_services) {

        def serviceCatalog = customer_rax_services["serviceCatalog"]
        def token = customer_rax_services["token"]

        assert serviceCatalog && token

        def cloudServers = []
        if (serviceCatalog.find { it.name == cloudServers }) {
            cloudServers = serviceCatalog.find { it -> it.name == "cloudServers" }.endpoints
        }

        def cloudServersOpenStack = []
        if (serviceCatalog.find { it.name == "cloudServersOpenStack" }) {
            cloudServersOpenStack = serviceCatalog.find { it -> it.name == "cloudServersOpenStack" }.endpoints
        }

        def result = [:]
        def cloudMonitoring = []
        if (serviceCatalog.find { it.name == "cloudMonitoring" }) {
            cloudMonitoring = serviceCatalog.find { it -> it.name == "cloudMonitoring" }.endpoints
        }else {
            println "No Rackspace cloud monitoring endpoints found for username: ${config.username}"
            return result;
        }

        def firstgen_boxes = []

        if (cloudServers.size() > 0 ) {
            // fetch all the firstgen boxes that do not have the agent and their status
            firstgen_boxes = fetch(cloudServers[0].publicURL, "/servers/detail", token)

            firstgen_boxes.servers.each { box ->
                box["timestamp"] = System.currentTimeMillis()
                box["generation"] = "firstgen"
                box["region"] = "dfw" // all firstgen boxes are in DFW
                result << ["$box.id": box]
            }
        }

        def th = []

        // fetch all the v2 boxes in each data center
        cloudServersOpenStack.each { endpoint ->
            th << Thread.start {
                fetch(endpoint.publicURL, "/servers/detail", token).servers.each { box ->
                    box["generation"] = "nextgen"
                    // syd.servers.api
                    box["region"] = (endpoint.publicURL as String).substring("https://".length(), "https://".length() + 3)
                    box["timestamp"] = System.currentTimeMillis()
                    result << ["$box.id": box]
                }
            }
        }
        th*.join()

        def entities = fetch(cloudMonitoring[0].publicURL, "/entities", token)

        if( ! entities) {
            println "no entities found for cloud monitoring public url ${cloudMonitoring[0].publicURL}, api username is ${config.username}"
            return result;
        }

        println "Found ${entities.values.size()} servers using endpoint: ${cloudMonitoring[0].publicURL}/entities"

        entities = entities.values

        th = []

        def servers_with_metrics = []

        entities.each { entity ->

            println "Getting checks/metrics for server $entity.label"
            def server_id = entity.uri.substring((entity.uri as String).lastIndexOf("/") + 1)

            def checks = fetch(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks/", token)

            def checks_metrics = [:]

            if (entity.agent_id && entity.id && checks && checks.values) {

                checks.values.each { check ->
                    // Ommitting load average and network API calls because they are not currently part of the over server health check
                    if (check.type.contains('agent.') && check.type != 'agent.load_average' && check.type != 'agent.network' ) {

                        def metrics = fetch(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks/$check.id/metrics", token)
                        println("Check type is: $check.type")

                        def to = DateTime.now().millis
                        def from = DateTime.now().minusSeconds(check.period * 3).millis

                        println "Date range from [$from] / to [$to] for metric check is from ${DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss").print(from)} to ${DateTimeFormat.forPattern("YYYY-MM-dd HH:mm:ss").print(to)}"

                        if (metrics && metrics.values) {

                            metrics.values.each { metric ->
                                // only get the data points we're interested in.
                                def do_fetch = false;
                                // We're going to leave in load_average and network here just in case we want to use it in the future
                                switch (check.type) {
                                    case "agent.load_average":
                                        do_fetch = metric.name == "1m"
                                        break
                                    case "agent.memory":
                                        do_fetch = metric.name == "actual_used" || metric.name == "total"
                                        break
                                    case "agent.cpu":
                                        do_fetch = metric.name == "stolen_percent_average" || metric.name == "usage_average"
                                        break
                                    case "agent.network":
                                        do_fetch = metric.name == "rx_bytes" || metric.name == "tx_bytes"
                                        break
                                    case "agent.filesystem":
                                        do_fetch = metric.name == "used" || metric.name == "total"
                                        break
                                }

                                if (do_fetch) {

                                    if (!servers_with_metrics.contains(entity.label)) {
                                        servers_with_metrics.add(entity.label)
                                    }

                                    // get the data points
                                    def data_points = fetch(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks/$check.id/metrics/$metric.name/plot", ["from": from, "to": to, "resolution": "FULL"], token)
                                    if (data_points && data_points.values) {
                                        metric.putAt("data_points", data_points.values)
                                    } else {
                                        println "No data points captured for server $entity.label, using check $check.type for metric $metric.name on endpoint: ${cloudMonitoring[0].publicURL}/entities/$entity.id/checks/$check.id/metrics/$metric.name/plot?from=$from&to=$to&resolution=FULL"
                                    }
                                }
                            }

                            check.putAt("metrics", metrics.values)
                            checks_metrics.putAt(check.id, check)
                        }
                    }
                }

                result.get("$server_id").putAt("checks_metrics", checks_metrics)
            }
            if( ! checks || ! checks.values || checks.values.length == 0) {
                println "No monitoring checks found on server $entity.label, continuing on to next server ... "
            }
        }

        result.put("servers_with_metrics", servers_with_metrics)

        result
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
        } else {
            println("Unable to fetch data: $res.statusLine.reasonPhrase")
            throw new Exception(res.statusLine.reasonPhrase)
            // println IOUtils.toString(res.entity.content)
        }
        null
    }

    def compute_median(values) {
        def numberItems = values.size()
        def sum = 0
        def modeMap = new HashMap<BigDecimal, Integer>()
        for (item in values) {
            sum += item
            if (modeMap.get(item) == null) {
                modeMap.put(item, 1)
            } else {
                count = modeMap.get(item) + 1
                modeMap.put(item, count)
            }
        }
        def mode = new ArrayList<Integer>()
        def modeCount = 0
        modeMap.each()
                { key, value ->
                    if (value > modeCount) {
                        mode.clear(); mode.add(key); modeCount = value
                    } else if (value == modeCount) {
                        mode.add(key)
                    }
                };
        def mean = sum / numberItems
        def midNumber = (int) (numberItems / 2)
        def median = numberItems % 2 != 0 ? values[midNumber] : (values[midNumber] + values[midNumber - 1]) / 2
        def minimum = Collections.min(values)
        def maximum = Collections.max(values)

        return median
    }

    def compute_network_rate(check, metric_name) {

        if (check.value.metrics.find {
            it.name == metric_name
        }.data_points.size() >= 2) {
            def val0 = check.value.metrics.find {
                it.name == metric_name
            }.data_points[0].average as BigDecimal

            def val1 = check.value.metrics.find {
                it.name == metric_name
            }.data_points[1].average as BigDecimal

            def t0 = (check.value.metrics.find {
                it.name == metric_name
            }.data_points[0].timestamp as BigDecimal) / 1000

            def t1 = (check.value.metrics.find {
                it.name == metric_name
            }.data_points[1].timestamp as BigDecimal) / 1000

            // 1 MBps 1048576

            return Math.round((val1 - val0) / (t1 - t0))
        }
        null
    }

    def process_actionables(checks_metrics) {

        def actionables = [:]

        if (checks_metrics) {
            checks_metrics.entrySet().each { check ->
                switch (check.value.type) {
                    case "agent.load_average":
                        if( getAverageDataPointsFor(check, "1m", false)) {
                            actionables << ["load": check.value.metrics.find { it.name == "1m" }.data_points[0].average]
                        }
                        break
                    case "agent.memory":
                        def total = getAverageDataPointsFor(check, "total", true)
                        def used = getAverageDataPointsFor(check, "actual_used", true)
                        if( total && used) {
                            actionables << ["mem": ((used * 100) / total) / 100]
                        }
                        break
                    case "agent.cpu":
                        if( getAverageDataPointsFor(check, "stolen_percent_average", false) ){
                            actionables << ["stolen": check.value.metrics.find {
                                it.name == "stolen_percent_average"
                            }.data_points[0].average / 100]
                        }
                        if( getAverageDataPointsFor(check, "usage_average", false)) {
                            actionables << ["cpu": check.value.metrics.find {
                                it.name == "usage_average"
                            }.data_points[0].average / 100]
                        }
                        break
                    case "agent.disk":
                        break
                    case "agent.network":
                        if (check.value.metrics.size() > 0) {
                            actionables << ["rx_bytes": compute_network_rate(check, "rx_bytes")]
                            actionables << ["tx_bytes": compute_network_rate(check, "tx_bytes")]
                        }
                        break
                    case "agent.filesystem":
                        def total = getAverageDataPointsFor(check, "total", true)
                        def used = getAverageDataPointsFor(check, "used", true)
                        if (total && used) {
                            actionables << ["fs": ((used * 100) / total) / 100]
                        }
                        break
                }
            }
        }

        actionables
    }

    def getAverageDataPointsFor( check, name, bigDecimalResult ) {
        if( check.value.metrics.size() > 0) {
            def datapoints = check.value.metrics.find {
                it.name == name
            }.data_points

            if( datapoints && datapoints.size() > 0 && datapoints[0].average ) {
                if( bigDecimalResult) {
                    return datapoints[0].average as BigDecimal
                }
                return datapoints[0].average
            }
        }
        null
    }

    def filter(config, data) {
        // label | string | required
        // timestamp | ms since epoch | required
        // vendor | string | required
        // region | string | required
        // public_ip | string | required
        // status (up/down) | bool | required
        // generation | string | optional => vendor specific
        // load_average | % | optional => agent
        // cpu usage | % | optional => agent
        // cpu stolen | % | optional => agent
        // memory usage | % | optional => agent
        // disk usage | % | optional => agent
        // disk io | ms | optional => agent
        // tx_bytes | bytes | optional => agent
        // rx_bytes | bytes | optional => agent

        def filtered_data = [:]

        data.entrySet().each { server ->
            def actionables = process_actionables(server.value.checks_metrics)
            def filtered =
                    [
                            "label"       : server.value.name,
                            "timestamp"   : server.value.timestamp,
                            "vendor"      : "rackspace",
                            "region"      : server.value.region,
                            "public_ip"   : server.value.accessIPv4 ? server.value.accessIPv4 : server.value.addresses.public[0],
                            "status"      : compute_status(server.value) ? "UP" : "DOWN",
                            "generation"  : server.value.generation,
                            "load_average": actionables.load,
                            "cpu_usage"   : actionables.cpu,
                            "cpu_stolen"  : actionables.stolen,
                            "mem_usage"   : actionables.mem,
                            "disk_usage"  : actionables.fs,
                            "tx_bytes"    : actionables.tx_bytes,
                            "rx_bytes"    : actionables.rx_bytes
                    ]

            // remove empties
            filtered.entrySet().removeAll(filtered.entrySet().findAll { it.value == null })

            // health diagnostic only if you have installed the agent
            if (server.value.checks_metrics) {
                filtered << ["health": compute_health_diagnostic(filtered)]
            }

            filtered_data << ["$server.key": filtered]
        }

        // future: HOPX scriptability (Post this payload to hopx, provision an app)

        filtered_data
    }

    def compute_health_diagnostic(data) {
        // All must be true
        if ((data.status == "UP")
                && (data.cpu_usage ? data.cpu_usage * 100 <= 20 : true)
                && (data.cpu_stolen ? data.cpu_stolen * 100 <= 15 : true)
                && (data.mem_usage ? data.mem_usage * 100 <= 30 : true)
                && (data.disk_usage ? data.disk_usage * 100 <= 30 : true)) {
            return "5"
        }

        // all must be true
        if ((data.status == "UP")
                && (data.cpu_usage ? data.cpu_usage * 100 <= 50 : true)
                && (data.cpu_stolen ? data.cpu_stolen * 100 <= 20 : true)
                && (data.mem_usage ? data.mem_usage * 100 <= 50 : true)
                && (data.disk_usage ? data.disk_usage * 100 <= 50 : true)) {
            return "4"
        }

        // if all are true, most likely fine to route traffic
        if ((data.status == "UP")
                && (data.cpu_usage ? data.cpu_usage * 100 <= 70 : true)
                && (data.cpu_stolen ? data.cpu_stolen * 100 <= 30 : true)
                && (data.mem_usage ? data.mem_usage * 100 <= 70 : true)
                && (data.disk_usage ? data.disk_usage * 100 <= 70 : true)) {
            return "3"
        }

        // if all are true, we are approaching an usable server
        if ((data.status == "UP")
                && (data.cpu_usage ? data.cpu_usage * 100 <= 95 : true)
                && (data.cpu_stolen ? data.cpu_stolen * 100 <= 60 : true)
                && (data.mem_usage ? data.mem_usage * 100 <= 95 : true)
                && (data.disk_usage ? data.disk_usage * 100 <= 80 : true)) {
            return "2"
        }


        // if any true, it's up but it will be very slow
        if ((data.status == "UP")
                && (data.cpu_usage ? data.cpu_usage * 100 < 150 : true)
                && (data.cpu_stolen ? data.cpu_stolen * 100 < 75 : true)
                && (data.mem_usage ? data.mem_usage * 100 < 150 : true)
                && (data.disk_usage ? data.disk_usage * 100 < 95 : true)) {
            return "1"
        }

        // Server is down or unusable, don't route traffic
        "0"
    }

    def compute_status(server) {
        server.status == "ACTIVE"
    }

    def recipe_config() {
        [
                name: "Rackspace",
                description: "Server Monitoring Metrics",
                run_every: 3600,
                identifier: "x.username",
                fields:
                        [
                                ["name": "username", "displayName": "Username", "fieldType": "text"],
                                ["name": "api_key", "displayName": "API Key", "fieldType": "text"]
                        ],
                screens:
                        [
                                [
                                        header: "Enter your Rackspace Credentials",
                                        fields: ["username", "api_key"],
                                        submit: "auth"
                                ]
                        ]
        ]
    }

}