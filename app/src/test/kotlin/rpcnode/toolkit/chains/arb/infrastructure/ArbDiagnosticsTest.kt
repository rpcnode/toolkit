package rpcnode.toolkit.chains.arb.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticRequest

class ArbDiagnosticsTest
{
    private val pruned = """WARN [10-13|21:15:13.848] error reading inbox                      err="pruned history unavailable""""
    private val limited = """WARN [10-13|21:15:17.018] error reading inbox                      err="429 Too Many Requests: {\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32005,\"message\":\"Rate limit exceeded.\"}}""""

    @Test
    fun `pruned and rate limited inbox reads are an error with the cause`()
    {
        val r = ArbDiagnostics.classify("arb_l1", listOf(pruned, limited, pruned).joinToString("\n"))
        assertEquals("error", r.level)
        assertTrue("2 × «pruned history unavailable»" in r.detail)
        assertTrue("1 × 429" in r.detail)
    }

    @Test
    fun `a log without inbox errors is fine`()
    {
        assertEquals("ok", ArbDiagnostics.classify("arb_l1", "INFO [x] Imported new chain segment").level)
    }

    @Test
    fun `old errors followed by a long healthy stretch no longer count`()
    {
        val log = pruned + "\n" + List(400) { "INFO [x] Imported new chain segment" }.joinToString("\n")
        assertEquals("ok", ArbDiagnostics.classify("arb_l1", log).level)
    }

    @Test
    fun `missing log is unknown`()
    {
        val r = ArbDiagnostics.parentChain(DiagnosticRequest(id = "arb_l1")) { null }
        assertEquals("unknown", r.level)
    }
}
