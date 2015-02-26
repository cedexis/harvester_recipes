import static junit.framework.Assert.assertFalse
import static junit.framework.Assert.assertNotNull
import static junit.framework.Assert.assertTrue

import org.junit.Test

/**
 * Created by grillo on 2/25/15.
 */
class LimelightTest extends BaseRecipeTest{

    @Test
    def void testValidateServicesWithValidServices() {
        def result = get_recipe().invokeMethod("validServices", [services:"hls,mss,flash"])
        assertTrue result
    }

    @Test
    def void testValidateServicesFailsWithInvalidServices() {
        def result = get_recipe().invokeMethod("validServices", [services:"hls,mms,flash"])
        assertFalse result
    }

    @Test
    def void testValidateServicesOkWithNoServices() {
        def result = get_recipe().invokeMethod("validServices", [url:"http://www.somewhere.com"])
        assertTrue result
    }

    @Test
    def void testValidateServicesFailsWithInvalidDelimeter() {
        def result = get_recipe().invokeMethod("validServices", [services:"hls mss flash"])
        assertFalse result
    }

    @Test
    def void testValidateServicesRemovesWhitespace() {
        def config = [services:"hls, mss, flash"]
        get_recipe().invokeMethod("validServices", config)
        assert config['services'] == 'hls,mss,flash'
    }

    @Test
    def void testValidateServicesFailsWithHttpOrHttps() {
        def config = [services:"http"]
        def result = get_recipe().invokeMethod("validServices", config)
        assertFalse result

        config = [services:"https"]
        result = get_recipe().invokeMethod("validServices", config)
        assertFalse result
    }

    @Override
    String getRecipeName() {
        return "limelight"
    }

}
