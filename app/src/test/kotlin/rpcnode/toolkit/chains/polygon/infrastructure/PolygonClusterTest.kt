package rpcnode.toolkit.chains.polygon.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolygonClusterTest
{
    @Test
    fun amoy_heimdall_chain_id()
    {
        val c = PolygonClusters.lookup("amoy")
        assertEquals("80002", c.chainId)
        assertEquals("heimdallv2-80002", c.heimdallChainId)
        assertEquals("amoy", c.borNetwork)
    }

    @Test
    fun mainnet_heimdall_chain_id()
    {
        val c = PolygonClusters.lookup("mainnet")
        assertEquals("137", c.chainId)
        assertEquals("heimdallv2-137", c.heimdallChainId)
    }

    @Test
    fun amoy_heimdall_api_port_differs_from_mainnet()
    {
        assertEquals(1327, PolygonPortTable.forEnv("amoy").heimdallApi)
        assertEquals(1317, PolygonPortTable.forEnv("mainnet").heimdallApi)
        assertTrue(PolygonPortTable.forEnv("amoy").http != PolygonPortTable.forEnv("mainnet").http)
    }
}
