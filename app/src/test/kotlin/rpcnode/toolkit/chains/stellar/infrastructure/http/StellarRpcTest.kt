package rpcnode.toolkit.chains.stellar.infrastructure.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

class StellarRpcTest
{
    private val healthy = """{"jsonrpc":"2.0","id":1,"result":{"status":"healthy","latestLedger":64927483,"latestLedgerCloseTime":"1791923197","oldestLedger":64806524,"ledgerRetentionWindow":120960}}"""
    private val notInit = """{"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"data stores are not initialized"}}"""
    private val behind = """{"jsonrpc":"2.0","id":1,"error":{"code":-32603,"message":"latency (45s) since last known ledger closed is too high (>30s)"}}"""
    private val latest = """{"jsonrpc":"2.0","id":1,"result":{"id":"3cd6","protocolVersion":29,"sequence":64927486,"closeTime":"1791923212"}}"""

    private fun http(health: String?, ledger: String? = null): SimpleHttp = SimpleHttp(
        HttpClient(
            MockEngine { req ->
                val body = (req.body as TextContent).text
                val answer = if ("getHealth" in body) health else ledger
                if (answer == null) respondError(HttpStatusCode.BadGateway)
                else respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ),
    )

    @Test
    fun health_body_has_no_params_and_the_samples_parse()
    {
        assertFalse("params" in StellarRpc.healthBody())
        val ok = StellarRpc.parseHealth(healthy)!!
        assertTrue(ok.ok)
        assertEquals(64927483L, ok.latestLedger)
        assertEquals(64806524L, ok.oldestLedger)
        assertTrue(StellarRpc.parseHealth(notInit)!!.initializing)
        assertTrue(StellarRpc.parseHealth(behind)!!.behind)
        assertNull(StellarRpc.parseHealth("garbage"))
        assertEquals(64927486L, StellarRpc.parseLatestSequence(latest))
    }

    @Test
    fun height_probe_reports_healthy_behind_and_initialising() = runTest {
        suspend fun reading(h: SimpleHttp) = StellarNodeHeightProbe(h).reading("/n", 42560, "", "mainnet")
        assertEquals(64927483L, reading(http(healthy))!!.height)
        assertEquals(false, reading(http(healthy))!!.syncing)
        val b = reading(http(behind, latest))!!
        assertEquals(64927486L, b.height)
        assertEquals(true, b.syncing)
        val i = reading(http(notInit))!!
        assertEquals(0L, i.height)
        assertEquals(true, i.syncing)
        assertNull(reading(http(null)))
        assertNull(StellarNodeHeightProbe(http(healthy)).reading("/n", 0, "", "mainnet"))
    }

    @Test
    fun tip_probe_takes_the_first_url_that_answers() = runTest {
        assertEquals(64927483L, StellarNetworkTipProbe(http(healthy)).tip(listOf("", "https://rpc.test")))
        assertEquals(64927486L, StellarNetworkTipProbe(http(behind, latest)).tip(listOf("https://rpc.test")))
        assertNull(StellarNetworkTipProbe(http(null)).tip(listOf("https://rpc.test")))
    }
}
