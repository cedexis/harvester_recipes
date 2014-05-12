import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.apache.commons.io.IOUtils
import org.apache.http.Header
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.client.utils.URIBuilder
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.joda.time.DateTime

@Grapes([
@Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3'),
@Grab(group='joda-time', module='joda-time', version='2.3')
])
class Rackspace {

    def API_AUTH_URL_V2 = "https://identity.api.rackspacecloud.com/v2.0/tokens";
    def token
    def serviceCatalog

    def run(config) {

        if( ! auth(config) ) {
            throw new Exception("Authentication failed")
        }

        def server_data = payload(config)

        def result = filter(config, server_data)

        result.findAll {
            it.value.keySet().intersect(["load_average",
                    "ping_average",
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
                    "ping_average",
                    "cpu_usage",
                    "cpu_stolen",
                    "mem_usage",
                    "disk_usage",
                    "tx_bytes",
                    "rx_bytes",
                    "health"])
        }

        if( ! servers_with_agents || servers_with_agents.size() == 0) {
            throw new Exception("There are no servers that contain at least one of the following monitoring agents: [load_average, ping_average, cpu_usage, cpu_stolen, mem_usage, disk_usage, tx_bytes, rx_bytes]");
        }

        def units = ["load_average":"load",
                "ping_average": "ms",
                "cpu_usage":"%",
                "cpu_stolen":"%",
                "mem_usage":"%",
                "disk_usage":"%",
                "tx_bytes":"bps",
                "rx_bytes":"bps",
                "health": "0-2"]

        def formats = ["load_average":"%s",
                "ping_average": "%s",
                "cpu_usage":"%.2f",
                "cpu_stolen":"%.2f",
                "mem_usage":"%.2f",
                "disk_usage":"%.2f",
                "tx_bytes":"%s",
                "rx_bytes":"%s",
                "health": "%s"]

        def metrics = servers_with_agents.collect {
            def label = it.value.label.replaceAll("\\.","_")

            it.value.subMap(["load_average",
                    "ping_average",
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

        new JsonBuilder(metrics).toPrettyString()
    }

    def auth(creds) {
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
                token = auth.access.token.id
                serviceCatalog = auth.access.serviceCatalog
                if(! checkUserRoles(auth)) {
                    throw new Exception("Authorization failed: Rackspace user ${auth.access.user.name} does not have the cloud monitoring role enabled")
                }
                break;
            default:
                throw new Exception("Authentication failed: $response.statusLine.reasonPhrase")
        }

        token != null && serviceCatalog != null
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

    def payload(config) {

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
            firstgen_boxes = fetch(cloudServers[0].publicURL, "/servers/detail")

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
                fetch(endpoint.publicURL, "/servers/detail").servers.each { box ->
                    box["generation"] = "nextgen"
                    // syd.servers.api
                    box["region"] = (endpoint.publicURL as String).substring("https://".length(), "https://".length() + 3)
                    box["timestamp"] = System.currentTimeMillis()
                    result << ["$box.id": box]
                }
            }
        }
        th*.join()

        def entities = fetch(cloudMonitoring[0].publicURL, "/entities")

        if( ! entities) {
            println "no entities found for cloud monitoring public url ${cloudMonitoring[0].publicURL}, api username is ${config.username}"
            return result;
        }

        entities = entities.values

        th = []

        entities.each { entity ->

            def server_id = entity.uri.substring((entity.uri as String).lastIndexOf("/") + 1)

            def checks = fetch(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks/")

            def checks_metrics = [:]

            if (entity.agent_id && entity.id && checks && checks.values) {

                /*
                    GG - taking a different approach, removing the required checks and POST endpoint calls, no auto-provisioning of metrics, removing anything that would cause the customer to incur additional charges.
                    We are read-only consumers of whatever monitors the customer has setup in Rackspace.   This recipe  monitors load_average, memory, cpu, network, filesystem and ping metrics.  If the customer has any
                    of these monitors setup on their servers, we can collect data for use by an OM app.  If none of these monitors are available, we fail in the run method.
                */

                checks.values.each { check ->

                    def metrics = fetch(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks/$check.id/metrics")

                    def to = DateTime.now().millis
                    def from = DateTime.now().minusSeconds(check.period * 3).millis

                    if( metrics && metrics.values ) {

                        metrics.values.each { metric ->
                            // only get the data points we're interested in.
                            def do_fetch = false;

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
                                case "remote.ping":
                                    do_fetch = (metric.name as String).contains(".average")
                                    break
                            }

                            if (do_fetch) {
                                // get the data points
                                def data_points = fetch(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks/$check.id/metrics/$metric.name/plot", ["from": from, "to": to, "resolution": "FULL"])
                                if (data_points && data_points.values) {
                                    metric.putAt("data_points", data_points.values)
                                }
                            }
                        }

                        check.putAt("metrics", metrics.values)
                        checks_metrics.putAt(check.id, check)
                    }
                }

                result.get("$server_id").putAt("checks_metrics", checks_metrics)
            }
        }

        result

    }

    def fetch(endpoint, uri_base, params = [:]) {

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
            //throw new Exception(res.statusLine.reasonPhrase)
            println IOUtils.toString(res.entity.content)
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
        // ping rtt average across regions | ms | optional => ping service
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
                            "label": server.value.name,
                            "timestamp": server.value.timestamp,
                            "vendor": "rackspace",
                            "region": server.value.region,
                            "public_ip": server.value.accessIPv4 ? server.value.accessIPv4 : server.value.addresses.public[0],
                            "status": compute_status(server.value) ? "UP" : "DOWN",
                            "generation": server.value.generation,
                            "load_average": actionables.load,
                            "ping_average": compute_ping_average(server.value),
                            "cpu_usage": actionables.cpu,
                            "cpu_stolen": actionables.stolen,
                            "mem_usage": actionables.mem,
                            "disk_usage": actionables.fs,
                            "tx_bytes": actionables.tx_bytes,
                            "rx_bytes": actionables.rx_bytes
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
        if ((data.status == "UP")
                && (data.cpu_usage ? data.cpu_usage * 100 < 80 : true)
                && (data.cpu_stolen ? data.cpu_stolen * 100 < 10 : true)
                && (data.mem_usage ? data.mem_usage * 100 < 80 : true)
                && (data.disk_usage ? data.disk_usage * 100 < 50 : true)
                && (data.ping_average ? data.ping_average < 100 : true)) {
            return "2"
        }

        if ((data.status == "UP")
                && ((data.cpu_usage ? data.cpu_usage * 100 > 80 && data.cpu_usage * 100 < 90 : true)
                || (data.cpu_stolen ? data.cpu_stolen * 100 > 20 && data.cpu_stolen * 100 < 30 : true)
                || (data.mem_usage ? data.mem_usage * 100 > 80 && data.mem_usage * 100 < 90 : true)
                || (data.disk_usage ? data.disk_usage * 100 > 50 && data.disk_usage * 100 < 80 : true)
                || (data.ping_average ? data.ping_average > 100 && data.ping_average < 1000 : true))) {
            return "1"
        }

        "0"
    }

    def compute_ping_average(server) {
        def average_duration = 0
        def total_duration = 0

        if (server.checks_metrics) {
            server.checks_metrics.entrySet().each {
                if (it.value.type == "remote.ping" && it.value.metrics.size() > 0
                        && it.value.metrics.any { metric -> metric.name.contains(".average") && metric.data_points && metric.data_points.size() > 0 }) {
                    def counter = 0
                    it.value.metrics.each { metric ->
                        if (metric.name.contains(".average")) {
                            total_duration += metric.data_points.first().average
                            counter += 1
                        }
                    }

                    // mean across zones
                    if (counter > 0) {
                        average_duration = total_duration / counter
                    }
                }
            }
        }

        if (average_duration > 0)
            return Math.round(((average_duration as BigDecimal) * 1000)) // reporting in ms not in seconds
        null
    }

    def compute_status(server) {
        server.status == "ACTIVE"
    }

    def recipe_config() {
        [
                name: "Rackspace",
                description: "Server Monitoring",
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