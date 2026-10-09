package rpcnode.toolkit.agent.application.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientUpdateCombinedLogTest
{
    @Test
    fun job_steps_come_first_then_the_node_log()
    {
        val snap = ClientUpdateSnapshot(
            jobLog = listOf("[10:00:00] stopping unit", "[10:00:03] unit stopped in 3s"),
            logTail = "line a\nline b",
        )
        val log = snap.combinedLog()
        assertTrue(log.startsWith("[10:00:00] stopping unit\n[10:00:03] unit stopped in 3s"), log)
        assertTrue(log.contains("--- node log (last lines) ---\nline a\nline b"), log)
    }

    @Test
    fun an_empty_snapshot_has_no_log()
    {
        assertEquals("", ClientUpdateSnapshot().combinedLog())
    }

    @Test
    fun a_job_without_node_log_shows_only_the_steps()
    {
        val log = ClientUpdateSnapshot(jobLog = listOf("[10:00:00] accepted")).combinedLog()
        assertEquals("[10:00:00] accepted", log)
    }
}
