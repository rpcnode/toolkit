package rpcnode.toolkit.chains.cardano.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoClusters

class CardanoIndexerMetricsTest
{
    // Ogmios /metrics exactly as in the v7.0.0 monitoring docs
    private val ogmios = """
        # TYPE ogmios_active_connections gauge
        ogmios_active_connections  0.0
        # TYPE ogmios_connected gauge
        ogmios_connected  1.0
        # TYPE ogmios_network_synchronization gauge
        ogmios_network_synchronization  0.99999
        # TYPE ogmios_tip_block counter
        ogmios_tip_block  7756720
        # TYPE ogmios_tip_slot counter
        ogmios_tip_slot  71603161
    """.trimIndent()

    // db-sync: gauge names from Cardano.DbSync.Metrics (prometheus-haskell text exposition)
    private val dbsync = """
        # TYPE cardano_db_sync_db_block_height gauge
        cardano_db_sync_db_block_height 9100000.0
        # TYPE cardano_db_sync_db_epoch_sync_duration_seconds gauge
        cardano_db_sync_db_epoch_sync_duration_seconds 41.5
        # TYPE cardano_db_sync_db_epoch_sync_number gauge
        cardano_db_sync_db_epoch_sync_number 520.0
        # TYPE cardano_db_sync_db_queue_length gauge
        cardano_db_sync_db_queue_length 12.0
        # TYPE cardano_db_sync_db_slot_height gauge
        cardano_db_sync_db_slot_height 120000000.0
        # TYPE cardano_db_sync_node_block_height gauge
        cardano_db_sync_node_block_height 14064221.0
    """.trimIndent()

    @Test
    fun parses_ogmios()
    {
        val m = assertNotNull(CardanoOgmiosMetrics.parse(ogmios))
        assertTrue(m.connected)
        assertEquals(0.99999, m.sync!!, 1e-9)
        assertEquals(7756720L, m.tipBlock)
        assertEquals(0L, m.activeConnections)
        assertFalse(CardanoOgmiosMetrics.parse("ogmios_connected 0.0")!!.connected)
        assertNull(CardanoOgmiosMetrics.parse("something_else 1"))
        assertNull(CardanoOgmiosMetrics.parse(null))
    }

    @Test
    fun parses_db_sync()
    {
        val m = assertNotNull(CardanoDbSyncMetrics.parse(dbsync))
        assertEquals(9_100_000L, m.dbBlock)
        assertEquals(120_000_000L, m.dbSlot)
        assertEquals(14_064_221L, m.nodeBlock)
        assertEquals(12L, m.queue)
        assertEquals(520L, m.epoch)
        assertNull(CardanoDbSyncMetrics.parse("cardano_db_sync_db_block_height 1.0"), "needs the slot too")
        assertNull(CardanoDbSyncMetrics.parse(""))
    }

    @Test
    fun db_sync_progress_is_the_slot_against_the_wall_clock()
    {
        val cluster = CardanoClusters.lookup("preview")
        val now = cluster.shelleyStartUnix + 1_000_000
        val behind = CardanoDbSyncMetrics.progress(CardanoDbSyncMetrics.Reading(1, 250_000, null, null, null), cluster, now)
        assertFalse(behind.atTip)
        assertEquals(25.0, behind.pct, 0.001)
        assertEquals(750_000L, behind.lagSlots)
        val tip = CardanoDbSyncMetrics.progress(CardanoDbSyncMetrics.Reading(1, 999_900, null, null, null), cluster, now)
        assertTrue(tip.atTip)
        assertEquals(100.0, tip.pct)
    }
}
