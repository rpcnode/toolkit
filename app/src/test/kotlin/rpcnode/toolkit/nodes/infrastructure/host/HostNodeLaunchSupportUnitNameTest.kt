package rpcnode.toolkit.nodes.infrastructure.host

import kotlin.test.Test
import kotlin.test.assertEquals

class HostNodeLaunchSupportUnitNameTest
{
    @Test
    fun unit_name_is_network_and_env()
    {
        assertEquals("rpcnode-tron-nile.service", HostNodeLaunchSupport.unitName("tron", "nile"))
        assertEquals("rpcnode-tron-nile.service", HostNodeLaunchSupport.unitName("TRON", "Nile"))
        assertEquals("rpcnode-bitcoin-mainnet.service", HostNodeLaunchSupport.unitName("bitcoin", "mainnet"))
    }
}
