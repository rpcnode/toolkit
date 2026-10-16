package rpcnode.toolkit.chains.etc.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals

class EtcClustersTest
{
    @Test
    fun lookup_maps_mordor_and_defaults_to_mainnet()
    {
        assertEquals(EtcCluster("mordor", "--mordor", "63", "7", 1024), EtcClusters.lookup("Mordor"))
        assertEquals(EtcCluster("mainnet", "--classic", "61", "1", 4096), EtcClusters.lookup("mainnet"))
        assertEquals("mainnet", EtcClusters.lookup("anything").env)
    }

    @Test
    fun node_mode_reads_install_options()
    {
        assertEquals("archive", etcNodeMode("""{"node":"archive"}"""))
        assertEquals("full", etcNodeMode("""{"node":"x"}"""))
        assertEquals("full", etcNodeMode(null))
        assertEquals("full", etcNodeMode("not json"))
    }
}
