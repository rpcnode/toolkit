package rpcnode.toolkit.chains.cometbft.infrastructure.http

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CometbftHttpTest
{
    private val status = """{"jsonrpc":"2.0","id":-1,"result":{"node_info":{"network":"osmosis-1"},
        "sync_info":{"latest_block_hash":"AB","latest_block_height":"72476692","latest_block_time":"2026-10-13T20:37:43.49Z","earliest_block_height":"1","catching_up":false}}}"""

    @Test
    fun parses_status()
    {
        val s = CometbftStatus.parse(status)
        assertNotNull(s)
        assertEquals(72476692L, s.height)
        assertFalse(s.catchingUp)
        assertEquals("osmosis-1", s.network)
        assertNull(CometbftStatus.parse("{}"))
        assertNull(CometbftStatus.parse(null))
    }

    @Test
    fun reading_flags_catching_up_and_stale_blocks()
    {
        val s = CometbftStatus.parse(status)!!
        val fresh = CometbftNodeHeightProbe.toReading(s, Instant.parse("2026-10-13T20:37:50Z"))
        assertFalse(fresh.syncing)
        val stale = CometbftNodeHeightProbe.toReading(s, Instant.parse("2026-10-13T21:37:50Z"))
        assertTrue(stale.syncing)
        val zero = CometbftNodeHeightProbe.toReading(s.copy(height = 0, catchingUp = true), Instant.now())
        assertEquals(-1L, zero.height)
    }

    @Test
    fun snapshot_discovery_parsers()
    {
        assertEquals(
            "https://hel1.your-objectstorage.com/osmosis/osmosis-1/snapshots/v31/a.tar.lz4",
            CometbftSnapshotResolver.parseLatestText("https://hel1.your-objectstorage.com/osmosis/osmosis-1/snapshots/v31/a.tar.lz4\n"),
        )
        assertEquals(
            "http://x/injective_mainnet_1.tar.lz4",
            CometbftSnapshotResolver.parseStatusJson("""{"snapshot":{"url":"http://x/injective_mainnet_1.tar.lz4","block_height":"1"}}"""),
        )
        val page = """<a href="https://snapshots.polkachu.com/snapshots/sei/sei_100.tar.lz4">a</a> <a href="https://snapshots.polkachu.com/snapshots/sei/sei_237.tar.lz4">b</a>"""
        assertEquals("https://snapshots.polkachu.com/snapshots/sei/sei_237.tar.lz4", CometbftSnapshotResolver.parsePolkachu(page, "sei", false))
        assertNull(CometbftSnapshotResolver.parsePolkachu(page, "sei", true))
        assertNull(CometbftSnapshotResolver.parsePolkachu(null, "sei", false))
    }
}
