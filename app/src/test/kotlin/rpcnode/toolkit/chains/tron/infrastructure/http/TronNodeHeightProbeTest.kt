package rpcnode.toolkit.chains.tron.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TronNodeHeightProbeTest
{
    @Test
    fun parse_getnowblock_number()
    {
        val body = """
            {"block_header":{"raw_data":{"number":1234567,"timestamp":1}}}
        """.trimIndent()
        assertEquals(1234567L, parseTronBlockHeight(body))
    }

    @Test
    fun parse_missing_number_is_null()
    {
        assertNull(parseTronBlockHeight("""{"block_header":{"raw_data":{}}}"""))
        assertNull(parseTronBlockHeight("not-json"))
    }
}
