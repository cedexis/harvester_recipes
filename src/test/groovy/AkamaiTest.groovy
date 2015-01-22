import junit.framework.Assert
import org.junit.Test

class AkamaiTest extends BaseRecipeTest {

    @Test
    def void testGetUsage() {
        def usage = [["product_name": "bam", "unit":"GB", "value" : 0.2],
                     ["product_name": "NetStorage", "unit":"GB", "value" : 0.1]]

        def result = get_recipe().invokeMethod("parse_usage_for_netstorage", usage)

        assert result != null
        assert 0.1 == result.value
    }

    @Test
    def void testNetStorageValuesAreAggregatedAcrossContracts() {
        def usage = [["product_name": "foo", "unit":"GB", "value" : 0.2],
                     ["product_name": "NetStorage", "unit":"GB", "value" : 0.1],
                     ["product_name": "foo", "unit":"GB", "value" : 0.3],
                     ["product_name": "NetStorage", "unit":"GB", "value" : 0.5],
                     ["product_name": "boo", "unit":"GB", "value" : 0.3],
                     ["product_name": "NetStorage", "unit":"GB", "value" : 2.01],
                     ["product_name": "boo", "unit":"GB", "value" : 1.9],
                     ["product_name": "NetStorage", "unit":"GB", "value" : 0.3],
                     ["product_name": "whoo", "unit":"GB", "value" : 4.0],
                     ["product_name": "NetStorage", "unit":"GB", "value" : 0.2]
        ]

        def result = get_recipe().invokeMethod("parse_usage_for_netstorage", usage)

        assert result != null
        assert 3.11 == result.value
    }

    @Override
    String getRecipeName() {
        return "akamai"
    }
}
