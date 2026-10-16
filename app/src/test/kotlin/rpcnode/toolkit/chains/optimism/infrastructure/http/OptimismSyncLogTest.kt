package rpcnode.toolkit.chains.optimism.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OptimismSyncLogTest
{
    private val esc = "\u001B"

    private fun status(stage: String, checkpoint: Long, target: Long, pct: String) =
        "$esc[2m2026-10-13T17:48:54.719302Z$esc[0m $esc[32m INFO$esc[0m Status $esc[3mconnected_peers$esc[0m$esc[2m=$esc[0m0 " +
            "$esc[3mstage$esc[0m$esc[2m=$esc[0m$stage $esc[3mcheckpoint$esc[0m$esc[2m=$esc[0m$checkpoint " +
            "$esc[3mtarget$esc[0m$esc[2m=$esc[0m$target $esc[3mstage_progress$esc[0m$esc[2m=$esc[0m$pct"

    @Test
    fun `execution stage is the live executed block over the pipeline target`()
    {
        val log = listOf(
            status("Execution", 11407030, 50041330, "2.78%"),
            "t=2026-10-13T20:48:55+0300 lvl=info msg=\"attempting connection\" peer=16Uiu2HAm",
            "$esc[2m2026-10-13T17:49:11Z$esc[0m INFO Executed block range $esc[3mstart$esc[0m$esc[2m=$esc[0m11438285 " +
                "$esc[3mend$esc[0m$esc[2m=$esc[0m11448986 $esc[3mthroughput$esc[0m$esc[2m=$esc[0m\"1.69Ggas/second\"",
        ).joinToString("\n")
        val pct = assertNotNull(OptimismSyncLog.pipelinePct(log))
        assertEquals(11448986.0 / 50041330 * 100.0, pct, 1e-9)
    }

    @Test
    fun `plain log without escape bytes is read too`()
    {
        val log = "[2m2026Z[0m INFO Status [3mconnected_peers[0m[2m=[0m3 [3mstage[0m[2m=[0mExecution " +
            "[3mcheckpoint[0m[2m=[0m25000000 [3mtarget[0m[2m=[0m50000000 [3mstage_progress[0m[2m=[0m50%"
        assertEquals(50.0, assertNotNull(OptimismSyncLog.pipelinePct(log)), 1e-9)
    }

    @Test
    fun `other stages report their own progress`()
    {
        assertEquals(41.5, assertNotNull(OptimismSyncLog.pipelinePct(status("MerkleExecute", 1, 2, "41.5%"))), 1e-9)
    }

    @Test
    fun `a live status line without a stage means the pipeline is done`()
    {
        val log = status("Execution", 1, 100, "1%") + "\n" +
            "INFO Status connected_peers=5 latest_block=50055600"
        assertNull(OptimismSyncLog.pipelinePct(log))
    }

    @Test
    fun `no status line means unknown`()
    {
        assertNull(OptimismSyncLog.pipelinePct("t=2026 lvl=info msg=hello"))
        assertNull(OptimismSyncLog.pipelinePct(null))
    }
}
