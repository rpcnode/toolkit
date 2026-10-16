package rpcnode.toolkit.chains.cardano.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoClusters

class CardanoMetricsTest
{
    private val body = """
        # TYPE cardano_node_metrics_blockNum_int gauge
        cardano_node_metrics_blockNum_int 13012345
        cardano_node_metrics_slotNum_int 190000000
        cardano_node_metrics_density_real 0.05
    """.trimIndent()

    @Test
    fun `parses block and slot`()
    {
        val tip = CardanoMetrics.parse(body)
        assertNotNull(tip)
        assertEquals(13012345L, tip.block)
        assertEquals(190000000L, tip.slot)
        assertNull(CardanoMetrics.parse("foo 1"))
        assertNull(CardanoMetrics.parse(null))
    }

    @Test
    fun `caught up node reports 100 percent`()
    {
        val cluster = CardanoClusters.lookup("mainnet")
        val now = cluster.shelleyStartUnix + 1_000_000
        val slot = cluster.shelleyStartSlot + 1_000_000 - 20
        val r = CardanoMetrics.reading(CardanoMetrics.Tip(100, slot), cluster, now)
        assertFalse(r.syncing)
        assertEquals(100.0, r.syncPct)
    }

    @Test
    fun `lagging node reports a percentage`()
    {
        val cluster = CardanoClusters.lookup("preview")
        val now = cluster.shelleyStartUnix + 1_000_000
        val r = CardanoMetrics.reading(CardanoMetrics.Tip(100, 250_000), cluster, now)
        assertTrue(r.syncing)
        assertEquals(25.0, r.syncPct!!, 0.001)
    }

    @Test
    fun `koios tip body`()
    {
        assertEquals(13012345L, CardanoNetworkTipProbe.parse("""[{"block_no":13012345,"epoch_no":600}]"""))
        assertNull(CardanoNetworkTipProbe.parse("[]"))
        assertNull(CardanoNetworkTipProbe.parse("oops"))
    }

    @Test
    fun `mithril log progress`()
    {
        val p = CardanoMithrilProgress.parseLog("Files: 120/480\nFiles 240 / 480")
        assertNotNull(p)
        assertEquals(50.0, p.pct, 0.001)
        assertNull(CardanoMithrilProgress.parseLog("nothing here"))
    }

    @Test
    fun `snapshot url sentinel`()
    {
        assertTrue(CardanoSnapshotResolver.isOfficialUrl("mithril://preprod"))
        assertEquals("preprod", CardanoSnapshotResolver.parse("mithril://preprod")?.env)
        assertEquals("mainnet", CardanoSnapshotResolver.parse("mithril://weird")?.env)
        assertNull(CardanoSnapshotResolver.parse("https://x"))
    }
}
