package rpcnode.toolkit.chains.ton.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticRequest
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.HostFacts

class TonDiagnosticsTest
{
    private fun status(ts: String, lag: String) =
        "[ 1][t16][$ts.938241143][manager.cpp:2759][!manager]\tSTATUS: last_masterchain_block_ago=$lag last_known_key_block_ago=14.1h shard_client_ago=14.1h\n"

    @Test
    fun slow_catch_up_warns_with_eta()
    {
        // 14.1h → 13.9h over 34 min (the real numbers): ~0.35 h closed per hour, ~40 h to go
        val log = status("2026-10-11 17:35:50", "14.1h") + status("2026-10-11 18:09:50", "13.9h")
        val r = TonDiagnostics.catchUp(DiagnosticRequest("ton_catchup", params = mapOf("warnHours" to "12")), logText = { log })
        assertEquals("warn", r.level)
        assertTrue(r.title.contains("13.9 h"), r.title)
    }

    @Test
    fun not_catching_up_warns()
    {
        val log = status("2026-10-11 17:00:00", "2.0h") + status("2026-10-11 18:00:00", "2.5h")
        val r = TonDiagnostics.catchUp(DiagnosticRequest("ton_catchup"), logText = { log })
        assertEquals("warn", r.level)
        assertTrue(r.title.contains("not catching up"), r.title)
    }

    @Test
    fun fast_catch_up_is_info_and_in_sync_is_ok()
    {
        val fast = status("2026-10-11 17:00:00", "3.0h") + status("2026-10-11 18:00:00", "1.0h")
        assertEquals("info", TonDiagnostics.catchUp(DiagnosticRequest("ton_catchup"), logText = { fast }).level)
        val synced = status("2026-10-11 18:00:00", "12s")
        assertEquals("ok", TonDiagnostics.catchUp(DiagnosticRequest("ton_catchup"), logText = { synced }).level)
    }

    @Test
    fun advertised_lan_address_is_an_error()
    {
        // 192.168.2.136 as signed int
        val lan = (192 shl 24) or (168 shl 16) or (2 shl 8) or 136
        val cfg = """{"addrs":[{"@type":"engine.addr","ip":$lan,"port":30311}]}"""
        val facts = HostFacts(publicIp = { "89.151.134.118" }, localIps = { listOf("192.168.2.136") })
        val r = TonDiagnostics.advertisedIp(DiagnosticRequest("ton_advertised_ip", port = 30311), facts) { cfg }
        assertEquals("error", r.level)
        assertTrue(r.detail.contains("192.168.2.136:30311"), r.detail)
    }

    @Test
    fun advertised_public_address_is_ok()
    {
        val pub = (89 shl 24) or (151 shl 16) or (134 shl 8) or 118
        val cfg = """{"addrs":[{"ip":$pub,"port":30311}]}"""
        val facts = HostFacts(publicIp = { "89.151.134.118" }, localIps = { listOf("192.168.2.136") })
        assertEquals("ok", TonDiagnostics.advertisedIp(DiagnosticRequest("ton_advertised_ip", port = 30311), facts) { cfg }.level)
    }
}
