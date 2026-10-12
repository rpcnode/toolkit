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

    @Test
    fun milliseconds_are_not_minutes()
    {
        // the real synced node: «1396.5ms» used to be read as 1396 minutes = "23 h behind"
        val log = status("2026-10-12 05:41:18", "1396.5ms")
        val r = TonDiagnostics.catchUp(DiagnosticRequest("ton_catchup"), logText = { log })
        assertEquals("ok", r.level)
        assertEquals(1.3965, TonDiagnostics.statusReadings(log).single().second, 1e-6)
        assertEquals(1.3965, TonValidatorLag.lastLagSeconds(log)!!, 1e-6)
    }

    @Test
    fun http_api_on_the_wrong_port_is_an_error_with_the_fix()
    {
        val ss = """
            LISTEN 0      2048      127.0.0.1:8081       0.0.0.0:*    users:(("ton-http-api",pid=3014921,fd=13),("ton-http-api",pid=376573,fd=13))
            LISTEN 0      4096      127.0.0.1:631        0.0.0.0:*    users:(("cupsd",pid=1,fd=7))
        """.trimIndent()
        val r = TonDiagnostics.httpApi(DiagnosticRequest("ton_http_api", port = 8082), status = { null }, listening = { ss })
        assertEquals("error", r.level)
        assertTrue(r.title.contains("8081") && r.title.contains("8082"), r.title)
        assertTrue(r.hint.contains("--port 8082"), r.hint)
        assertEquals(listOf(8081), TonDiagnostics.apiPorts(ss))
    }

    @Test
    fun http_api_ok_503_and_down()
    {
        fun run(code: Int?, ss: String = "") =
            TonDiagnostics.httpApi(DiagnosticRequest("ton_http_api", port = 8082), status = { code }, listening = { ss }).level
        assertEquals("ok", run(200))
        assertEquals("info", run(503))
        assertEquals("warn", run(null))
    }

    private fun config(hashHex: String): String
    {
        val b64 = java.util.Base64.getEncoder().encodeToString(hashHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        return """{"validator":{"zero_state":{"workchain":-1,"seqno":0,"root_hash":"$b64"}}}"""
    }

    @Test
    fun a_testnet_node_on_a_mainnet_config_is_an_error()
    {
        // the real host: «testnet» node, /usr/bin/ton/global.config.json is the mainnet one (17A3A929…)
        val r = TonDiagnostics.network(DiagnosticRequest("ton_network", params = mapOf("env" to "testnet"))) { config(TonClusters.zeroStateHash("mainnet")) }
        assertEquals("error", r.level)
        assertTrue(r.title.contains("syncs mainnet"), r.title)
        assertEquals("ok", TonDiagnostics.network(DiagnosticRequest("ton_network", params = mapOf("env" to "testnet"))) { config(TonClusters.zeroStateHash("testnet")) }.level)
        assertEquals("ok", TonDiagnostics.network(DiagnosticRequest("ton_network", params = mapOf("env" to "mainnet"))) { config(TonClusters.zeroStateHash("mainnet")) }.level)
    }

    @Test
    fun zero_state_reads_the_base64_hash_as_hex()
    {
        // F6OpKZKq… is the base64 the real mainnet global.config.json carries
        val cfg = """{"validator":{"zero_state":{"root_hash":"F6OpKZKqvqeFp6CQmFomXNMfMj2EnaUSOXN+Mh+wVWk="}}}"""
        assertEquals("17A3A92992AABEA785A7A090985A265CD31F323D849DA51239737E321FB05569", TonDiagnostics.zeroState(cfg))
    }
}
