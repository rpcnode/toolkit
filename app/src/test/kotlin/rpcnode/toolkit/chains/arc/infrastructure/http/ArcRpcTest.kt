package rpcnode.toolkit.chains.arc.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArcRpcTest
{
    @Test
    fun parses_the_hex_block_number()
    {
        assertEquals(0x1fL, ArcRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"result":"0x1f"}"""))
        assertEquals(0L, ArcRpc.parseBlockNumber("""{"result":"0x"}"""))
        assertNull(ArcRpc.parseBlockNumber("""{"error":{"code":-32000,"message":"x"}}"""))
        assertNull(ArcRpc.parseBlockNumber(null))
    }
}
