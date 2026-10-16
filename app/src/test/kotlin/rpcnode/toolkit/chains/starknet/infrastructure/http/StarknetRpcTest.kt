package rpcnode.toolkit.chains.starknet.infrastructure.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

class StarknetRpcTest
{
    @Test
    fun block_number_is_a_plain_json_integer_not_hex()
    {
        // verbatim answers of starknet-rpc.publicnode.com and api.zan.top (member order differs)
        assertEquals(16337586L, StarknetRpc.parseBlockNumber("""{"jsonrpc":"2.0","result":16337586,"id":1}"""))
        assertEquals(16337586L, StarknetRpc.parseBlockNumber("""{"result":16337586,"id":1,"jsonrpc":"2.0"}"""))
        // tolerated: a quoted decimal and a hex string (older spec revisions / proxies)
        assertEquals(16337586L, StarknetRpc.parseBlockNumber("""{"jsonrpc":"2.0","result":"16337586","id":1}"""))
        assertEquals(0xF9A0B2L, StarknetRpc.parseBlockNumber("""{"jsonrpc":"2.0","result":"0xf9a0b2","id":1}"""))
    }

    @Test
    fun an_empty_db_or_garbage_gives_no_height()
    {
        assertNull(StarknetRpc.parseBlockNumber("""{"jsonrpc":"2.0","error":{"code":32,"message":"There are no blocks"},"id":1}"""))
        assertNull(StarknetRpc.parseBlockNumber("""{"jsonrpc":"2.0","result":-1,"id":1}"""))
        assertNull(StarknetRpc.parseBlockNumber("""{"jsonrpc":"2.0","result":{"a":1},"id":1}"""))
        assertNull(StarknetRpc.parseBlockNumber("<html>"))
        assertNull(StarknetRpc.parseBlockNumber(""))
        assertNull(StarknetRpc.parseBlockNumber(null))
    }

    @Test
    fun syncing_false_means_caught_up()
    {
        // verbatim answer of a synced node
        val s = assertNotNull(StarknetRpc.parseSyncing("""{"jsonrpc":"2.0","result":false,"id":1}"""))
        assertFalse(s.syncing)
        assertEquals(100.0, s.pct)
    }

    @Test
    fun syncing_object_gives_the_progress()
    {
        val s = assertNotNull(
            StarknetRpc.parseSyncing(
                """{"jsonrpc":"2.0","id":1,"result":{"starting_block_hash":"0x1","starting_block_num":15807098,
                    "current_block_hash":"0x2","current_block_num":16000000,"highest_block_hash":"0x3","highest_block_num":16337586}}""",
            ),
        )
        assertTrue(s.syncing)
        assertEquals(16000000L, s.currentBlock)
        assertEquals(16337586L, s.highestBlock)
        assertEquals(16000000 * 100.0 / 16337586, s.pct!!, 1e-9)
        // hex numbers (spec <= 0.7 NUM_AS_HEX) are read too
        val hex = assertNotNull(
            StarknetRpc.parseSyncing("""{"result":{"current_block_num":"0x10","highest_block_num":"0x20"}}"""),
        )
        assertEquals(50.0, hex.pct)
        assertNull(StarknetRpc.parseSyncing("""{"error":{"code":-32601,"message":"Method not found"}}"""))
        assertNull(StarknetRpc.parseSyncing("""{"result":"yes"}"""))
    }

    @Test
    fun height_probe_reading_covers_synced_syncing_and_unknown()
    {
        val synced = StarknetNodeHeightProbe.toReading(100, StarknetRpc.Syncing(syncing = false, pct = 100.0))
        assertEquals(100L, synced.height)
        assertFalse(synced.syncing)
        assertEquals(100.0, synced.syncPct)
        val behind = StarknetNodeHeightProbe.toReading(90, StarknetRpc.Syncing(true, currentBlock = 92, highestBlock = 100, pct = 92.0))
        assertEquals(92L, behind.height)
        assertTrue(behind.syncing)
        assertEquals(92.0, behind.syncPct)
        // never claims 100 % while still syncing
        assertEquals(99.9, StarknetNodeHeightProbe.toReading(99, StarknetRpc.Syncing(true, 99, 100, 100.0)).syncPct)
        val unknown = StarknetNodeHeightProbe.toReading(77, null)
        assertEquals(77L, unknown.height)
        assertNull(unknown.syncPct)
        assertFalse(unknown.syncing)
    }

    @Test
    fun height_and_tip_probes_call_the_rpc_over_http() = runTest {
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            calls += body
            val answer = when
            {
                "starknet_blockNumber" in body -> """{"jsonrpc":"2.0","result":16337586,"id":1}"""
                "starknet_syncing" in body -> """{"jsonrpc":"2.0","result":false,"id":1}"""
                else -> "{}"
            }
            respond(answer, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = SimpleHttp(HttpClient(engine))
        val reading = StarknetNodeHeightProbe(http).reading("/n", 42530, "", "mainnet")
        assertEquals(16337586L, reading?.height)
        assertEquals(100.0, reading?.syncPct)
        assertTrue(calls.any { it.contains("\"method\":\"starknet_blockNumber\"") })
        assertNull(StarknetNodeHeightProbe(http).reading("/n", 0, "", "mainnet"), "no port, no reading")
        assertEquals(16337586L, StarknetNetworkTipProbe(http).tip(listOf("", "https://starknet-rpc.publicnode.com")))
    }
}
