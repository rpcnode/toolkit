package rpcnode.toolkit.agent.infrastructure.catalog

import kotlin.test.Test
import kotlin.test.assertTrue

class CatalogFixedPortsReaderTest
{
    @Test
    fun reads_fixed_ports_out_of_the_shipped_clients_yaml()
    {
        val ports = CatalogFixedPortsReader().read()

        // TRON mainnet P2P and Bitcoin mainnet P2P — both come straight out of chains/<id>/clients.yml.
        assertTrue(18888 in ports, "expected tron mainnet p2p (18888) in $ports")
        assertTrue(8333 in ports, "expected bitcoin mainnet p2p (8333) in $ports")
        assertTrue(ports == ports.sorted().distinct(), "ports must be sorted and de-duplicated")
    }
}
