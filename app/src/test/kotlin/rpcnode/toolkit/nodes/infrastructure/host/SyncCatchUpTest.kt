package rpcnode.toolkit.nodes.infrastructure.host

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncCatchUpTest
{
    @Test
    fun bar_follows_the_closed_share_of_the_first_lag()
    {
        // the real BSC mainnet node: started 12.7 M behind, now 9.98 M behind
        assertEquals(21.2, SyncCatchUp.pct(12_658_637.0, 9_975_482.0), 0.1)
        assertEquals(0.0, SyncCatchUp.pct(100.0, 100.0))
        assertEquals(99.9, SyncCatchUp.pct(100.0, 0.0))
        assertEquals(0.0, SyncCatchUp.pct(0.0, 0.0))
    }

    @Test
    fun start_lag_is_kept_across_restarts_and_raised_only_when_the_lag_grows()
    {
        val dir = Files.createTempDirectory("node").toString()
        assertEquals(1000.0, SyncCatchUp.startLag(dir, "x", 1000.0))
        assertEquals(1000.0, SyncCatchUp.startLag(dir, "x", 400.0))
        assertEquals(1500.0, SyncCatchUp.startLag(dir, "x", 1500.0))
        assertEquals(1500.0, SyncCatchUp.startLag(dir, "x", 700.0))
    }
}
