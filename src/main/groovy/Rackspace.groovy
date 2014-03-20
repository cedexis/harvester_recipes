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
        auth(config)
        def server_data = payload(config)

        def result = filter(config, server_data)

        // third pass at the data, only extract values that vary

        result.findAll {
            it.value.keySet().containsAll(["load_average",
                    "ping_average",
                    "cpu_usage",
                    "cpu_stolen",
                    "mem_usage",
                    "disk_usage",
                    "tx_bytes",
                    "rx_bytes"])
        }.collectEntries {

            it

        }

        def servers_vith_agents = result.findAll {
            it.value.keySet().containsAll(["load_average",
                    "ping_average",
                    "cpu_usage",
                    "cpu_stolen",
                    "mem_usage",
                    "disk_usage",
                    "tx_bytes",
                    "rx_bytes"])
        }

        def units = ["load_average":"load",
                "ping_average": "ms",
                "cpu_usage":"%",
                "cpu_stolen":"%",
                "mem_usage":"%",
                "disk_usage":"%",
                "tx_bytes":"bps",
                "rx_bytes":"bps"]

        def formats = ["load_average":"%s",
                "ping_average": "%s",
                "cpu_usage":"%.2f",
                "cpu_stolen":"%.2f",
                "mem_usage":"%.2f",
                "disk_usage":"%.2f",
                "tx_bytes":"%s",
                "rx_bytes":"%s"]

        def metrics = servers_vith_agents.collect {
            def label = it.value.label.replaceAll("\\.","_")

            it.value.subMap(["load_average",
                    "ping_average",
                    "cpu_usage",
                    "cpu_stolen",
                    "mem_usage",
                    "disk_usage",
                    "tx_bytes",
                    "rx_bytes"]).collectEntries {
                ["${label}_${it.key}": [
                        "unit": units.get(it.key),
                        "value": String.format(formats.get(it.key) as String, it.value)
                ]
                ]
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
                break;
            default:
                throw new Exception("Authentication failed: $response.statusLine.reasonPhrase")
        }

        token != null && serviceCatalog != null
    }

    def agents() {
        def agents = []
        fetch("/agents").values.each { it ->
            agents << it.id
        }
        return agents
    }

    def payload(config) {
        if (token && serviceCatalog) {

            def cloudServers = serviceCatalog.find { it -> it.name == "cloudServers" }.endpoints
            def cloudServersOpenStack = serviceCatalog.find { it -> it.name == "cloudServersOpenStack" }.endpoints
            def cloudMonitoring = serviceCatalog.find { it -> it.name == "cloudMonitoring" }.endpoints

            def result = [:]

            // fetch all the firstgen boxes that do not have the agent and their status
            def firstgen_boxes = fetch(cloudServers[0].publicURL, "/servers/detail")

            def th = []


            firstgen_boxes.servers.each { box ->
                box["timestamp"] = System.currentTimeMillis()
                box["generation"] = "firstgen"
                box["region"] = "dfw" // all firstgen boxes are in DFW
                result << ["$box.id": box]
            }

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

            def entities = fetch(cloudMonitoring[0].publicURL, "/entities").values

            th = []

            entities.each { entity ->


                def server_id = entity.uri.substring((entity.uri as String).lastIndexOf("/") + 1)

                // collect agent data (of the boxes that are up, otherwise there wont be any data to poll)
                if (entity.agent_id && result.get("$server_id").status == "ACTIVE") {
                    def agent_data = get_agent_data(cloudMonitoring[0].publicURL, entity.agent_id)
                    result.get("$server_id").putAt("agent_data", agent_data)
                }

                // check if any checks are available on that box
                def checks = fetch(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks/")

                def checks_metrics = [:]

                // if the checks are not setup, auto provision them that's $9 / month per box
                // B.2.1. agent.filesystem
                // B.2.2. agent.memory
                // B.2.3. agent.load_average
                // B.2.4. agent.cpu
                // B.2.5. agent.disk
                // B.2.6. agent.network

                // if an agent is installed, then we want to create all these checks to collect the most accurate data
                if (entity.agent_id) {
                    def required_checks = [
                            "remote.ping",
                            "agent.cpu",
                            "agent.memory",
                            "agent.load_average",
                            "agent.disk",
                            "agent.network",
                            "agent.filesystem",
                    ]

                    if (!required_checks.every { check_id -> checks.values.find { it.type == check_id } }) {
                        required_checks.each { check_id ->
                            println("Checking if entity has $check_id")
                            if (!checks.values.find { it.type == check_id }) {
                                switch (check_id) {
                                    case "agent.filesystem":
                                        // todo: check if this is a unix box :)
                                        post(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks",
                                                ["type": check_id, "details": ["target": "/"], "label": "fusion_$check_id".replaceAll("[\\W]", "_"), "period": 30])
                                        break
                                    case "agent.disk":
                                        result.get("$server_id").agent_data.filesystems.entrySet().findAll {
                                            it.key.startsWith "/dev/"
                                        }.each {
                                            post(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks",
                                                    ["type": check_id, "details": ["target": "$it.key"], "label": "fusion_${check_id}_${it.key}".replaceAll("[\\W]", "_"), "period": 30])
                                        }
                                        break
                                    case "agent.network":
                                        post(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks",
                                                ["type": check_id, "details": ["target": "eth0"], "label": "fusion_${check_id}".replaceAll("[\\W]", "_"), "period": 30])
                                        break
                                    case "remote.ping":
                                        // mzdfw, mzhkg, mziad, mzlon, mzord, mzsyd

                                        def mzs = fetch(cloudMonitoring[0].publicURL, "/monitoring_zones")

                                        //

                                        post(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks",
                                                ["type": check_id,
                                                        "details": [
                                                                "count": 5
                                                        ],
                                                        "monitoring_zones_poll": [
                                                                "mzdfw", "mzlon", "mzord"
                                                        ],
                                                        "label": "fusion_$check_id".replaceAll("[\\W]", "_"),
                                                        "period": 60,
                                                        "timeout": 30,
                                                        //"target_alias": "default"
                                                        "target_alias": "${entity.ip_addresses.entrySet().find { it.key.contains("public") && it.key.contains("v4") }.key}"
                                                ])
                                        break
                                    default:
                                        post(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks",
                                                ["type": check_id, "label": "fusion_$check_id".replaceAll("[\\W]", "_"), "period": 30])
                                        break
                                }
                            }
                        }
                    }

                    checks.values.each { check ->
                        // we're only interested in tcp / http checks
                        //if (!"$check.type".startsWith("agent")) {

                        def metrics = fetch(cloudMonitoring[0].publicURL, "/entities/$entity.id/checks/$check.id/metrics")

                        def to = DateTime.now().millis
                        def from = DateTime.now().minusSeconds(check.period * 3).millis

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
                                metric.putAt("data_points", data_points.values)
                            }

                        }

                        check.putAt("metrics", metrics.values)
                        checks_metrics.putAt(check.id, check)
                    }

                    //}

                    result.get("$server_id").putAt("checks_metrics", checks_metrics)
                }

            }

            //th*.join()

            result
        } else {
            throw new Exception("Authentication failed")
        }
    }

    def get_agent_data(endpoint, agent, categories = ["network_interfaces", "cpus", "memory", "filesystems", "disks"]) {
        println("fetching data for $agent")
        def agent_data = [:]
        // "network_interfaces", "cpus", "disks", "filesystems", "memory"
        categories.each { category ->


            def cat_data = fetch(endpoint, "/agents/$agent/host_info/$category")

            if (cat_data) {
                agent_data[category] = [:]
                agent_data["timestamp"] = cat_data.timestamp
                if (cat_data.info instanceof List) {
                    def items = [:]
                    cat_data.info.each { item ->
                        def fields_descriptor = [:]
                        item.entrySet().each { metric ->
                            fields_descriptor[metric.key] = metric.value
                        }
                        if (item.name) {
                            items[item.name] = fields_descriptor
                        } else if (item.dev_name) {
                            items[item.dev_name] = fields_descriptor
                        } else {
                            println("Unable to find a primary key for: $item")
                        }
                    }
                    agent_data[category] = items
                } else if (cat_data.info instanceof Map) {
                    agent_data[category] = cat_data.info
                }
            }


        }

        agent_data
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
            IOUtils.toString(res.entity.content)
        }
        null
    }

    def post(endpoint, uri_base, params = [:]) {

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

        res.getAllHeaders().each { header ->
            println("${(header as Header).name}:${(header as Header).value}")
        }

        if (res && (res.statusLine.statusCode as String).startsWith("2")) {

            // we're not really expecting data from POSTs
            //return new JsonSlurper().parseText(IOUtils.toString(res.entity.content))
        } else {
            println("Unable to fetch data: $res.statusLine.reasonPhrase")
            //throw new Exception(res.statusLine.reasonPhrase)
            IOUtils.toString(res.entity.content)
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
                        actionables << ["load": check.value.metrics.find { it.name == "1m" }.data_points[0].average]
                        break
                    case "agent.memory":
                        def total = check.value.metrics.find { it.name == "total" }.data_points[0].average as BigDecimal
                        def used = check.value.metrics.find {
                            it.name == "actual_used"
                        }.data_points[0].average as BigDecimal
                        actionables << ["mem": ((used * 100) / total) / 100]
                        break
                    case "agent.cpu":
                        actionables << ["stolen": check.value.metrics.find {
                            it.name == "stolen_percent_average"
                        }.data_points[0].average / 100]
                        actionables << ["cpu": check.value.metrics.find {
                            it.name == "usage_average"
                        }.data_points[0].average / 100]
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
                        def total = check.value.metrics.size() > 0 ? check.value.metrics.find {
                            it.name == "total"
                        }.data_points[0].average as BigDecimal : null
                        def used = check.value.metrics.size() > 0 ? check.value.metrics.find {
                            it.name == "used"
                        }.data_points[0].average as BigDecimal : null
                        if (total && used) {
                            actionables << ["fs": ((used * 100) / total) / 100]
                        }
                        break
                }

            }
        }

        // cpu stuff
        // name=stolen_percent_average
        // name=usage_average

        // agent.load_average

        actionables
    }

    def process_actionables2(server_data) {

        if (server_data && !server_data.isEmpty()) {

            def freshness = DateTime.now().millis - server_data.timestamp

            // usage
            def total_used = 0
            def total = 0
            server_data.cpus.entrySet().each { cpu ->
                total_used += new BigDecimal(cpu.value.total - cpu.value.idle)
                total += new BigDecimal(cpu.value.total)
            }
            def mean_cpu_usage = (total_used) / total

            def total_stolen = 0
            server_data.cpus.entrySet().each { cpu ->
                total_stolen += new BigDecimal(cpu.value.stolen)
            }
            def mean_cpu_stolen = (total_stolen) / total


            def BigDecimal mem_percent = (server_data.memory.actual_used) / server_data.memory.total
            def BigDecimal fs_percent = (server_data.filesystems["/dev/xvda1"].used) / server_data.filesystems["/dev/xvda1"].total
            def BigDecimal tx_bytes = server_data.network_interfaces["eth0"].tx_bytes
            def BigDecimal rx_bytes = server_data.network_interfaces["eth0"].rx_bytes

            // todo: trending?

            return [
                    "cpu": mean_cpu_usage,
                    "stolen": mean_cpu_stolen,
                    "fs": fs_percent,
                    "mem": mem_percent,
                    "tx_bytes": tx_bytes,
                    "rx_bytes": rx_bytes
            ]

        }

        [:]

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
            return "GREEN"
        }

        if ((data.status == "UP")
                && ((data.cpu_usage ? data.cpu_usage * 100 > 80 && data.cpu_usage * 100 < 90 : true)
                || (data.cpu_stolen ? data.cpu_stolen * 100 > 20 && data.cpu_stolen * 100 < 30 : true)
                || (data.mem_usage ? data.mem_usage * 100 > 80 && data.mem_usage * 100 < 90 : true)
                || (data.disk_usage ? data.disk_usage * 100 > 50 && data.disk_usage * 100 < 80 : true)
                || (data.ping_average ? data.ping_average > 100 && data.ping_average < 1000 : true))) {
            return "YELLOW"
        }

        "RED"
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