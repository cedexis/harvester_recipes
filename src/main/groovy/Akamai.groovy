import com.mashape.unirest.http.Unirest
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import org.joda.time.DateTime
import wslite.http.auth.HTTPBasicAuthorization
import wslite.soap.SOAPClient

/**
 *
 * Akamai API docs: https://developer.akamai.com/api/luna/billing-usage/reference.html
 *
 * Calls Akamai SOAP service to obtain consolidated bandwidth metrics
 * Calls Akamai restful services to obtain usage metrics
 *
 * Returns bandwidth and usage metrics to be used in OM app
 *
 * @author grillo
 * @since 6/2014
 */

@Grapes([
@Grab(group = 'com.mashape.unirest', module = 'unirest-java', version = '1.3.3'),
@Grab(group = 'joda-time', module = 'joda-time', version = '2.3'),
@Grab(group = 'com.github.groovy-wslite', module = 'groovy-wslite', version = '0.8.0')
])

@Slf4j
class Akamai {

    def run(config) {

        def sources = report_sources(config)
        def products = get_products(config, sources)
        def usage = contract_usage(config, products, sources)
        def bandwidth = get_consolidated(config)

        return new JsonBuilder(["bandwidth": bandwidth, "usage": parse_usage_for_netstorage(usage)]).toPrettyString()

    }

    def parse_usage_for_netstorage(usage) {
        def netstorage = [:]
        usage.each{ p ->
            if(p.product_name == 'NetStorage') {
                netstorage <<  [
                    unit: p.unit,
                    value: netstorage.value? netstorage.value + p.value : p.value
                ]
            }
        }
        netstorage
    }

    def auth(config) {
        if (config.keySet().containsAll(["username", "password"])) {
            def response = Unirest.get("https://control.akamai.com/contractusage/ws/api/v1/reportSources").basicAuth(config.username, config.password).asString()
            if (response.code == 200) {
                return new JsonSlurper().parseText(response.body).status.equalsIgnoreCase("ok")
            }
        }

        throw new RuntimeException("Invalid Credentials")
    }

    def get_consolidated(config) {
        def client = new SOAPClient('https://control.akamai.com/nmrws/services/RealtimeReports?wsdl')
        client.authorization = new HTTPBasicAuthorization(config.username, config.password)
        def response = client.send(SOAPAction: "",
                """<?xml version='1.0' encoding='UTF-8'?>
                   <soapenv:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:real="https://control.akamai.com/RealtimeReports.xsd">
                      <soapenv:Header/>
                      <soapenv:Body>
                         <real:getCPCodes soapenv:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"/>
                      </soapenv:Body>
                   </soapenv:Envelope>"""
        )

        def xml = new XmlSlurper().parseText(response.text)
        def cp_codes = xml.'**'.collectEntries {
            if (it.name() == "multiRef" && it.description.text() != "unused") {
                return [(it.cpcode.text() as String): [
                        description: it.description.text(),
                        service: it.service.text()
                ]]
            }
            [:]
        }

        [
            unit: "Mbps",
            value: String.format("%.2f", get_freeflow(client, cp_codes) + get_edgesuite(client, cp_codes))
        ]
    }

    def gauge(json) {
        def data = new JsonSlurper().parseText(json)
        data && data.bandwidth ? "${data.bandwidth.unit} ${String.format("%.2f", data.bandwidth.value)}" : ""
    }

    def get_edgesuite(client, cp_codes) {
        def response = client.send(SOAPAction: "",
                """<soapenv:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:real="https://control.akamai.com/RealtimeReports.xsd" xmlns:soapenc="http://schemas.xmlsoap.org/soap/encoding/">
                      <soapenv:Header/>
                      <soapenv:Body>
                         <real:getEdgeSuiteSummary soapenv:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                            <cpcodes xsi:type="real:ArrayOfInt" soapenc:arrayType="xsd:int[]">
                              ${cp_codes.keySet().collect { "<int>$it</int>" }.join('\n')}
                            </cpcodes>
                            <network xsi:type="xsd:string">all_es</network>
                            <contentType xsi:type="xsd:string">all</contentType>
                         </real:getEdgeSuiteSummary>
                      </soapenv:Body>
                   </soapenv:Envelope>"""
        )

        def xml = new XmlSlurper().parseText(response.text)
        def bps = xml.text().replaceAll("\n", "").split(",")[0] as Long
        def mbps = bps / 1048576

        mbps
    }

    def get_freeflow(client, cp_codes) {
        def response = client.send(SOAPAction: "",
                """<soapenv:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:real="https://control.akamai.com/RealtimeReports.xsd" xmlns:soapenc="http://schemas.xmlsoap.org/soap/encoding/">
                   <soapenv:Header/>
                   <soapenv:Body>
                      <real:getFreeFlowSummary soapenv:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                         <cpcodes xsi:type="real:ArrayOfInt" soapenc:arrayType="xsd:int[]">
                           ${cp_codes.keySet().collect { "<int>$it</int>" }.join('\n')}
                         </cpcodes>
                      </real:getFreeFlowSummary>
                   </soapenv:Body>
                </soapenv:Envelope>"""
        )

        def xml = new XmlSlurper().parseText(response.text)
        def bps = xml.text().replaceAll("\n", "").split(",")[0] as Long
        def mbps = bps / 1048576

        mbps
    }


    def contract_usage(config, products, sources) {
        def usage = []

        def startDate = [month: DateTime.now().monthOfYear, year: DateTime.now().year]
        def endDate = [month: DateTime.now().plusMonths(1).monthOfYear, year: DateTime.now().year]

        sources.contents.each { source ->
            products.contents.each { product ->

                def response = Unirest.get("https://control.akamai.com/contractusage/ws/api/v1/statisticTypes/${product.id}/${source.type}/${source.id}/${startDate.month}/${startDate.year}/${endDate.month}/${endDate.year}").basicAuth(config.username, config.password).asString()

                if (response.code == 200) {
                    def stat_types = new JsonSlurper().parseText(response.body)

                    stat_types.contents.each { stat_type ->

                        response = Unirest.get("https://control.akamai.com/contractusage/ws/api/v1/contractUsageData/monthly/${product.id}/${source.type}/${source.id}/${stat_type.statisticType}/${startDate.month}/${startDate.year}/${startDate.month}/${startDate.year}").basicAuth(config.username, config.password).asString()
                        if (response.code == 200) {
                            def value = new JsonSlurper().parseText(response.body).contents.series[0].orderedValues

                            usage << [
                                    product_id: product.id,
                                    product_name: product.name,
                                    stat: stat_type.statisticLabel,
                                    unit: stat_type.chartUnit,
                                    value: new JsonSlurper().parseText(value)[0]
                            ]
                        }
                    }
                }
            }
        }

        usage
    }

    def get_csv(products, sources) {
        def response = Unirest.post("https://control.akamai.com/contractusage/ws/api/v1/contractUsageData/csv").fields([
                reportSources: new JsonBuilder(sources.contents).toPrettyString(),
                products: new JsonBuilder(products.contents).toPrettyString(),
                startDate: new JsonBuilder([month: DateTime.now().monthOfYear, year: DateTime.now().year]).toPrettyString(),
                endDate: new JsonBuilder([month: DateTime.now().plusMonths(1).monthOfYear, year: DateTime.now().year]).toPrettyString()

        ]).basicAuth(config.username, config.password).asString()
        if (response.code == 200) {
            return new JsonSlurper().parseText(response.body)
        }
    }

    def get_measures(config, products, sources) {
        def measures = [sources: []]
        // /contractusage/ws/api/v1/measures/{productId}/{reportSourceType}/{reportSourceId}/{startMonth}/{startYear}/{endMonth}/{endYear}
        sources.contents.each { source ->
            products.contents.each { product ->
                def startDate = [month: DateTime.now().monthOfYear, year: DateTime.now().year]
                def endDate = [month: DateTime.now().plusMonths(1).monthOfYear, year: DateTime.now().year]
                def response = Unirest.get("https://control.akamai.com/contractusage/ws/api/v1/measures/${product.id}/${source.type}/${source.id}/${startDate.month}/${startDate.year}/${endDate.month}/${endDate.year}").basicAuth(config.username, config.password).asString()

                if (response.code == 200) {
                    def mes = new JsonSlurper().parseText(response.body).contents[0].measures

                    if (null == source["products"])
                        source["products"] = []

                    if (mes && mes.size() > 0) {
                        source["products"] << product + [
                                measures: mes
                        ]
                    }
                }
            }
            measures.sources << source
        }
        measures
    }

    def get_products(config, sources) {
        def response = Unirest.post("https://control.akamai.com/contractusage/ws/api/v1/products").fields([
                reportSources: new JsonBuilder(sources.contents).toPrettyString(),
                startDate: new JsonBuilder([month: DateTime.now().monthOfYear, year: DateTime.now().year]).toPrettyString(),
                endDate: new JsonBuilder([month: DateTime.now().plusMonths(1).monthOfYear, year: DateTime.now().year]).toPrettyString()

        ]).basicAuth(config.username, config.password).asString()
        if (response.code == 200) {
            return new JsonSlurper().parseText(response.body)
        }
    }

    def report_sources(config) {
        def response = Unirest.get("https://control.akamai.com/contractusage/ws/api/v1/reportSources").basicAuth(config.username, config.password).asString()
        if (response.code == 200) {
            return new JsonSlurper().parseText(response.body)
        }
    }


    def recipe_config() {
        [
                name: "Akamai",
                description: "Bandwidth and usage metrics",
                identifier: "x.username",
                run_every: 3600,
                fields:
                        [
                                ["name": "username", "displayName": "Username", "fieldType": "text", "i18n":"username"],
                                ["name": "password", "displayName": "Password", "fieldType": "text", "i18n":"password"],
                        ],
                screens:
                        [
                                [
                                        header: "Enter your Akamai Credentials",
                                        fields: ["username", "password"],
                                        submit: "auth"
                                ]
                        ]
        ]
    }
}
