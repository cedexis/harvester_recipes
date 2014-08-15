import com.mashape.unirest.http.utils.Base64Coder
import groovy.json.JsonSlurper
import org.apache.commons.io.IOUtils
import org.testng.annotations.Test

/**
 * Created by ben on 1/23/14.
 */
@Test
class TestRecipes {

    @Test
    def void testLevel3() {
        do_test("level3")
    }

    @Test
    def void testLimeLight() {
        do_test("limelight")
    }

    @Test
    def void testCdnetworks() {
        do_test("cdnetworks")
    }

    @Test
    def void testCloudfront() {
        do_test("cloudfront")
    }

    @Test
    def void testHighwinds() {
        do_test("highwinds")
    }

    @Test
    def void testAkamai() {
        do_test("akamai")
    }

    @Test
    def void testChinaCache() {
        do_test("chinacache")
    }

    @Test
    def void testEdgecast() {
        do_test("edgecast")
    }

    @Test
    def void testFastly() {
        do_test("fastly")
    }

    @Test
    def void testRackspace() {
        do_test("rackspace")
    }

    @Test
    def void testNewRelic() {
        do_test("newrelic")
    }

    @Test
    def void testMaxCDN() {
        do_test("maxcdn")
    }

    @Test
    def void testTcpPingHealthCheck() {
        do_test("tcppinghealthcheck", ['destination':'www.google.com', 'timeout':5000])
    }

    @Test
    def void testRackspacePing() {
        do_test("rackspaceping", ['system_account_user':'cedexis','system_account_api_key':'9b55d4abb3162cafe390d520a4dcf43c','cdx_zone_id':'1', 'cdx_customer_id':'1997', 'install_id':'81dc042a16722cdc563ccfdecbfb0375', 'entity_parent_label':'fusion','hostname':'portal.cedexis.com','timeout':22,'run_every':3600])
    }

    @Test
    def void testSimpleGet() {
        def script = Base64Coder.encodeString(new File("./src/main/groovy/SimpleGet.groovy").text)

        def config = [
                script: script,
                url: "http://www.google.com"
        ]

        def result = execute_groovy_task(config)

        assert result != null
        assert result.bytes.length < 10240
    }

    def do_test(name) {
        do_test(name, [:])
    }

    def do_test(name, test_config) {
        def creds = new JsonSlurper().parseText(new File("./src/test/resources/creds.json").text)

        new File("./src/main/groovy").eachFileMatch( ~"(?i)${name}.groovy" ) {
            def script = Base64Coder.encodeString(it.text)

            def config = [
                    script: script
            ]

            if( creds[name]) {
                config += creds[name]
            }

            config += test_config

            def result = execute_groovy_task(config)

            println result

            sleep(1000)

            assert result != null
            assert new JsonSlurper().parseText(result) != null
        }


    }

    def execute_groovy_task(config) {
        ClassLoader classLoader = getClass().getClassLoader()
        GroovyClassLoader loader = new GroovyClassLoader(classLoader)

        System.setProperty("groovy.grape.report.downloads", "true")
        //System.setProperty("ivy.cache.ttl.default", "1d")

        if (!new File("./grapeConfig.xml").exists()) {
            def grapeConfig = IOUtils.toString(classLoader.getResourceAsStream("grapeConfig.xml") as InputStream)
            new File("./grapeConfig.xml").write(grapeConfig)
        }

        System.setProperty("grape.config", "./grapeConfig.xml")

        groovy.grape.Grape.enableAutoDownload = true
        groovy.grape.Grape.enableGrapes = true

        Class groovyClass = loader.parseClass(Base64Coder.decodeString(config.script) as String)

        GroovyObject groovyObject = (GroovyObject) groovyClass.newInstance()

        if (groovyObject.metaClass.methods.find { it.name == "auth"}) {
            assert groovyObject.invokeMethod("auth", config) == true
        }

        def result = groovyObject.invokeMethod("run", config)

        result
    }

}
