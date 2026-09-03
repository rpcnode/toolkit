package rpcnode.toolkit.chains.polygon.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PolygonBorRpcTest
{
    @Test
    fun parse_block_number_hex()
    {
        val body = """{"jsonrpc":"2.0","id":1,"result":"0x10"}"""
        assertEquals(16L, PolygonBorRpc.parseBlockNumber(body))
    }

    @Test
    fun parse_block_number_null_on_error()
    {
        assertNull(PolygonBorRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"error":{"message":"x"}}"""))
        assertNull(PolygonBorRpc.parseBlockNumber(null))
        assertNull(PolygonBorRpc.parseBlockNumber(""))
    }

    @Test
    fun parse_hex_int64()
    {
        assertEquals(0L, PolygonBorRpc.parseHexInt64("0x0"))
        assertEquals(255L, PolygonBorRpc.parseHexInt64("0xff"))
        assertNull(PolygonBorRpc.parseHexInt64("zz"))
    }
}
