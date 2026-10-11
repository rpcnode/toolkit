package rpcnode.toolkit.nodes.infrastructure.host.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostNodeDiagnosticsTest
{
    @Test
    fun behind_nat_warns_with_the_forward_to_make()
    {
        val facts = HostFacts(publicIp = { "89.151.134.118" }, localIps = { listOf("172.17.0.1", "192.168.2.136") })
        val r = HostNodeDiagnostics.nat(DiagnosticRequest("nat", port = 30311, protocol = "udp"), facts)
        assertEquals("warn", r.level)
        assertTrue(r.hint.contains("UDP 30311 → 192.168.2.136:30311"), r.hint)
    }

    @Test
    fun public_ip_on_host_is_ok_and_unknown_check_is_reported()
    {
        val facts = HostFacts(publicIp = { "45.152.160.19" }, localIps = { listOf("45.152.160.19") })
        val out = HostNodeDiagnostics.run(
            listOf(DiagnosticRequest("nat", port = 8001), DiagnosticRequest("future_check")),
            facts,
        )
        assertEquals(listOf("ok", "unknown"), out.map { it.level })
    }
}

class PathMtuDiagnosticTest
{
    private fun facts(pathMtu: Int, ifaceMtu: Int = 1500, probing: Int = 0, icmp: Boolean = true) = HostFacts(
        publicIp = { null },
        localIps = { emptyList() },
        pingDf = { _, payload -> icmp && payload + 28 <= pathMtu },
        routeMtu = { ifaceMtu },
        tcpMtuProbing = { probing },
    )

    @Test
    fun pppoe_black_hole_is_found_with_the_exact_mtu()
    {
        // the real host: path 1492, interface 1500, probing off
        val r = HostNodeDiagnostics.pathMtu(DiagnosticRequest("path_mtu"), facts(pathMtu = 1492))
        assertEquals("warn", r.level)
        assertEquals("Path MTU 1492 < interface MTU 1500", r.title)
        assertTrue(r.hint.contains("tcp_mtu_probing = 1"), r.hint)
    }

    @Test
    fun matching_path_is_ok_probing_on_is_info_and_no_icmp_is_unknown()
    {
        assertEquals("ok", HostNodeDiagnostics.pathMtu(DiagnosticRequest("path_mtu"), facts(pathMtu = 1500)).level)
        assertEquals("info", HostNodeDiagnostics.pathMtu(DiagnosticRequest("path_mtu"), facts(pathMtu = 1492, probing = 1)).level)
        assertEquals("unknown", HostNodeDiagnostics.pathMtu(DiagnosticRequest("path_mtu"), facts(pathMtu = 1500, icmp = false)).level)
    }
}
