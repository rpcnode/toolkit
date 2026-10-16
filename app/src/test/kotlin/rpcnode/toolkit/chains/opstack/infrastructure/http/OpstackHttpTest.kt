package rpcnode.toolkit.chains.opstack.infrastructure.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.FakeGitHubTokenProvider
import rpcnode.toolkit.clients.infrastructure.github.HttpGitHubReleaseClient

class OpstackHttpTest
{
    private val esc = "\u001B"

    @Test
    fun block_number_parses_the_hex_result_of_a_real_reply()
    {
        assertEquals(61_178_578L, OpstackRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"result":"0x3a582d2"}"""))
        assertEquals(0L, OpstackRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"result":"0x"}"""))
        assertEquals(29_396_096L, OpstackRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"result":"0x1c08c80"}"""))
        assertNull(OpstackRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"method not found"}}"""))
        assertNull(OpstackRpc.parseBlockNumber("not json"))
        assertNull(OpstackRpc.parseBlockNumber(null))
        assertEquals("""{"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}""", OpstackRpc.blockNumberRequest())
    }

    private fun status(stage: String, checkpoint: Long, target: Long, pct: String) =
        "$esc[2m2026-10-13T17:48:54.719302Z$esc[0m $esc[32m INFO$esc[0m Status $esc[3mconnected_peers$esc[0m$esc[2m=$esc[0m0 " +
            "$esc[3mstage$esc[0m$esc[2m=$esc[0m$stage $esc[3mcheckpoint$esc[0m$esc[2m=$esc[0m$checkpoint " +
            "$esc[3mtarget$esc[0m$esc[2m=$esc[0m$target $esc[3mstage_progress$esc[0m$esc[2m=$esc[0m$pct"

    @Test
    fun the_reth_pipeline_progress_is_read_from_the_shared_log()
    {
        val log = listOf(
            status("Execution", 11407030, 50041330, "2.78%"),
            "t=2026-10-13T20:48:55+0300 lvl=info msg=\"attempting connection\" peer=16Uiu2HAm",
            "$esc[2m2026-10-13T17:49:11Z$esc[0m INFO Executed block range $esc[3mstart$esc[0m$esc[2m=$esc[0m11438285 " +
                "$esc[3mend$esc[0m$esc[2m=$esc[0m11448986 $esc[3mthroughput$esc[0m$esc[2m=$esc[0m\"1.69Ggas/second\"",
        ).joinToString("\n")
        assertEquals(11448986.0 / 50041330 * 100.0, assertNotNull(OpstackSyncLog.pipelinePct(log)), 1e-9)
        assertEquals(41.5, assertNotNull(OpstackSyncLog.pipelinePct(status("MerkleExecute", 1, 2, "41.5%"))), 1e-9)
        assertNull(OpstackSyncLog.pipelinePct(status("Execution", 1, 100, "1%") + "\nINFO Status connected_peers=5 latest_block=50055600"))
        assertNull(OpstackSyncLog.pipelinePct(null))
    }

    // newest first, as GitHub lists them: World Chain also tags proofs/v… (with rc) next to its node releases
    private val releases = """
        [
          {"tag_name":"v2.5.0-rc.1","draft":false,"prerelease":true,"assets":[]},
          {"tag_name":"v2.4.5","draft":false,"prerelease":false,"assets":[
            {"name":"world-chain-v2.4.5-x86_64-unknown-linux-gnu.tar.gz","browser_download_url":"https://github.com/worldcoin/world-chain/releases/download/v2.4.5/world-chain-v2.4.5-x86_64-unknown-linux-gnu.tar.gz"}]},
          {"tag_name":"v2.4.4","draft":false,"prerelease":false,"assets":[]},
          {"tag_name":"proofs/v1.0.0-rc.5","draft":false,"prerelease":true,"assets":[]},
          {"tag_name":"v2.4.3","draft":false,"prerelease":false,"assets":[]},
          {"tag_name":"proofs/v1.0.0-rc.1","draft":false,"prerelease":false,"assets":[]}
        ]
    """.trimIndent()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/repos/worldcoin/world-chain/releases") { ex ->
            val bytes = releases.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun the_world_chain_resolver_takes_the_highest_stable_v_tag_and_skips_proofs_and_release_candidates() = runTest {
        val github = HttpGitHubReleaseClient(FakeGitHubTokenProvider(null), apiBase = "http://127.0.0.1:${server.address.port}")
        val resolver = OpstackClientReleaseResolver(github)
        val release = assertNotNull(resolver.resolve(EnvId.MAINNET))
        assertEquals("v2.4.5", release.tag)
        assertEquals("2.4.5", release.version)
        assertEquals("worldcoin/world-chain", release.sourceLabel)
        assertEquals("v2.4.5", resolver.resolve(EnvId.SEPOLIA)?.tag)
        assertNull(resolver.resolve(EnvId.TESTNET), "world chain has no other env")
    }
}
