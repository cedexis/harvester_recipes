import com.mashape.unirest.http.utils.Base64Coder
import org.apache.commons.io.IOUtils

abstract class BaseRecipeTest {

    GroovyObject recipe

    abstract def String getRecipeName();

    def get_recipe() {
        if( recipe != null) {
            return recipe
        }
        ClassLoader classLoader = getClass().getClassLoader()
        GroovyClassLoader loader = new GroovyClassLoader(classLoader)

        System.setProperty("groovy.grape.report.downloads", "true")

        if (!new File("./grapeConfig.xml").exists()) {
            def grapeConfig = IOUtils.toString(classLoader.getResourceAsStream("grapeConfig.xml") as InputStream)
            new File("./grapeConfig.xml").write(grapeConfig)
        }

        System.setProperty("grape.config", "./grapeConfig.xml")

        groovy.grape.Grape.enableAutoDownload = true
        groovy.grape.Grape.enableGrapes = true

        def script
        new File("./src/main/groovy").eachFileMatch( ~"(?i)${getRecipeName()}.groovy" ) {
            script = Base64Coder.encodeString(it.text)
        }

        Class groovyClass = loader.parseClass(Base64Coder.decodeString(script) as String)

        recipe = (GroovyObject) groovyClass.newInstance()
    }

}
