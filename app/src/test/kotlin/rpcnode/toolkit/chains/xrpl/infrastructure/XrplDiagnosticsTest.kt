package rpcnode.toolkit.chains.xrpl.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticRequest

class XrplDiagnosticsTest
{
    private fun info(state: String, peers: Int, complete: String, uptime: Long) =
        """{"result":{"status":"success","info":{"server_state":"$state","peers":$peers,"complete_ledgers":"$complete","uptime":$uptime}}}"""

    private fun run(json: String?) = XrplDiagnostics.sync(DiagnosticRequest("xrpl_sync", port = 5006)) { json }

    @Test
    fun stuck_node_with_no_ledgers_is_an_error_with_the_real_numbers()
    {
        // the real host: connected, 2 peers, complete_ledgers empty, 10.8 h up
        val r = run(info("connected", 2, "empty", 38871))
        assertEquals("error", r.level)
        assertTrue(r.title.contains("10.8 h"), r.title)
        assertTrue(r.hint.contains("tcp_mtu_probing"), r.hint)
    }

    @Test
    fun an_hour_without_ledgers_is_a_warning_and_a_fresh_start_is_info()
    {
        assertEquals("warn", run(info("connected", 2, "empty", 3600)).level)
        assertEquals("info", run(info("connecting", 0, "empty", 300)).level)
    }

    @Test
    fun syncing_with_ledgers_is_info_and_full_is_ok()
    {
        assertEquals("info", run(info("syncing", 12, "100-500", 4000)).level)
        assertEquals("ok", run(info("full", 20, "100-5000", 90000)).level)
        assertEquals("ok", run(info("proposing", 20, "100-5000", 90000)).level)
    }

    @Test
    fun no_answer_is_reported()
    {
        assertEquals("warn", run(null).level)
        assertEquals("unknown", run("""{"result":{"status":"success"}}""").level)
    }
}
