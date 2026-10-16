package rpcnode.toolkit.chains.etc.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc.SyncingStatus
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc

class EtcSyncReadingTest
{
    @Test
    fun snap_sync_state_download_shows_the_log_percentage()
    {
        val r = EtcSyncReading.combine(0, SyncingStatus(syncing = false, blockPct = 100.0), 42.5)!!
        assertEquals(0L, r.height)
        assertEquals(42.5, r.syncPct)
        assertEquals(true, r.syncing)
    }

    @Test
    fun a_synced_node_is_100_percent()
    {
        val r = EtcSyncReading.combine(25_529_785, SyncingStatus(syncing = false, blockPct = 100.0), null)!!
        assertEquals(100.0, r.syncPct)
        assertEquals(false, r.syncing)
    }

    @Test
    fun syncing_takes_the_slower_of_block_ratio_and_log()
    {
        val r = EtcSyncReading.combine(1000, SyncingStatus(true, 1000, 2000, 50.0), 10.0)!!
        assertEquals(10.0, r.syncPct)
        val capped = EtcSyncReading.combine(1000, SyncingStatus(true, 1000, 1000, 100.0), 100.0)!!
        assertEquals(99.9, capped.syncPct)
    }

    @Test
    fun no_signal_is_null_and_the_vendor_log_line_parses()
    {
        assertNull(EtcSyncReading.combine(null, null, null))
        val log = "INFO [10-13|10:00:00.000] Syncing: state download in progress      synced=0.71% state=1.2GiB"
        assertEquals(0.71, EthereumEthRpc.parseSnapSyncPctFromLog(log))
    }
}
