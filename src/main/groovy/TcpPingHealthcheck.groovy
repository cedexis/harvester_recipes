import groovy.json.JsonBuilder
import groovy.util.logging.Slf4j

/**
 * Created by ben on 3/3/14.
 */
@Slf4j
class TcpPingHealthcheck {

    def run(config) {

        def result = [:]
        def th = []

        config.destination.split(",").each {

            final entry = it
            th << Thread.start {
                def connection
                def established = false
                try {
                    def hostname = entry.contains(":") ? entry.split("\\:")[0] : entry
                    def port = entry.contains(":") ? entry.split("\\:")[1] as Integer : 80
                    def server = new InetSocketAddress(
                            InetAddress.getByName(hostname as String),
                            port)
                    connection = new Socket();
                    connection.connect(server, config.timeout as Integer)
                    established = connection.isConnected()
                } catch (Exception e) {
                    e.printStackTrace()
                    System.err.println("Unable to open socket to $entry => $e.message");
                } finally {
                    if (connection)
                        connection.close()
                }
                result["${entry.trim()}_alive".replaceAll("[\\W]|_","_")] = ["value": established, "unit": "boolean"]
            }
        }

        th*.join()

        new JsonBuilder(result).toPrettyString()
    }

    def check_destination(config) {
        Integer.parseInt(config.timeout)
        if (config.destination == null) {
            throw new RuntimeException("Invalid destinations")
        }

        def hostname = config.destination
        if (config.destination.contains(":")) {
            hostname = config.destination.split("\\:")[0]
        }
        InetAddress.getByName(hostname)
        true
    }

//    def filter(config, List results) {
//        // if i failed n consecutive times => unhealthy
//        // if my current state is unhealthy,  n consecutive successes will deem me healthy
//
//        def sample = []
//        if (results.size() >= (config.unhealthy_threshold as Integer)) {
//            sample = results[0..config.unhealthy_threshold as Integer]
//        } else {
//            sample = results
//        }
//
//        def is_healthy = sample.collect {
//            it
//        }.every { it }
//
//        ["$config.destination": ["unit": "health_status", "value": "$is_healthy", "sample": "${sample.collect { it as Integer }}"]]
//
//    }

    def recipe_config() {
        [
                name: "Tcp Ping",
                description: "Attempt to open a TCP socket",
                identifier: "x.destination",
                run_every: 60,
		no_platform: true,
                fields:
                        [
                                ["name": "destination", "displayName": "Destination", "fieldType": "text"],
                                ["name": "timeout", "displayName": "Timeout", "fieldType": "text"]
                        ],
                screens:
                        [
                                [
                                        header: "Enter cname or ip address",
                                        fields: ["destination", "timeout"],
                                        submit: "check_destination"
                                ]
                        ]
        ]
    }
}
