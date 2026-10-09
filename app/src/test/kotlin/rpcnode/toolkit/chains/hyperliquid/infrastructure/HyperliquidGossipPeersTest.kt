package rpcnode.toolkit.chains.hyperliquid.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HyperliquidGossipPeersTest
{
    @Test
    fun parse_string_array()
    {
        val ips = HyperliquidGossipPeers.parseBody("""["10.0.0.1","10.0.0.2","10.0.0.1"]""")
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), ips)
    }

    @Test
    fun parse_root_node_ips_object()
    {
        val body = """
          {"root_node_ips":[{"Ip":"1.1.1.1"},{"Ip":"2.2.2.2"}],"try_new_peers":true}
        """.trimIndent()
        assertEquals(listOf("1.1.1.1", "2.2.2.2"), HyperliquidGossipPeers.parseBody(body))
    }

    @Test
    fun seeds_non_empty()
    {
        assertTrue(HyperliquidClusters.lookup("mainnet").seedPeers.isNotEmpty())
        assertTrue(HyperliquidClusters.lookup("testnet").seedPeers.isNotEmpty())
    }
}
