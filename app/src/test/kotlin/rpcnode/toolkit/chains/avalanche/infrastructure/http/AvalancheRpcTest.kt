package rpcnode.toolkit.chains.avalanche.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AvalancheRpcTest
{
    @Test
    fun parses_the_hex_block_number_and_rejects_errors()
    {
        assertEquals(0x4a3b2c1L, AvalancheRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"result":"0x4a3b2c1"}"""))
        assertEquals(0L, AvalancheRpc.parseBlockNumber("""{"result":"0x"}"""))
        assertNull(AvalancheRpc.parseBlockNumber("""{"error":{"code":-32000,"message":"boom"}}"""))
        assertNull(AvalancheRpc.parseBlockNumber(null))
        assertNull(AvalancheRpc.parseBlockNumber("not json"))
    }

    @Test
    fun reads_the_bootstrap_flag_of_the_info_api()
    {
        assertEquals(true, AvalancheRpc.parseBootstrapped("""{"jsonrpc":"2.0","result":{"isBootstrapped":true},"id":1}"""))
        assertEquals(false, AvalancheRpc.parseBootstrapped("""{"result":{"isBootstrapped":false}}"""))
        assertNull(AvalancheRpc.parseBootstrapped("""{"error":{"code":-32000}}"""))
        assertNull(AvalancheRpc.parseBootstrapped(""))
    }

    @Test
    fun c_chain_url_is_added_once()
    {
        assertEquals("http://127.0.0.1:9650/ext/bc/C/rpc", AvalancheRpc.cChainUrl("http://127.0.0.1:9650/"))
        assertEquals("https://api.avax.network/ext/bc/C/rpc", AvalancheRpc.cChainUrl("https://api.avax.network/ext/bc/C/rpc"))
    }
}
