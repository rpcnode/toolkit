package rpcnode.toolkit.chains.stacks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.nonevm.infrastructure.CatalogFixtures
import rpcnode.toolkit.chains.stacks.infrastructure.StacksBurnchain
import rpcnode.toolkit.chains.stacks.infrastructure.StacksChains
import rpcnode.toolkit.chains.stacks.infrastructure.StacksConfigToml
import rpcnode.toolkit.chains.stacks.infrastructure.StacksSettings
import rpcnode.toolkit.chains.stacks.infrastructure.StacksUnitBodies
import rpcnode.toolkit.chains.stacks.infrastructure.http.StacksClientReleaseResolver
import rpcnode.toolkit.chains.stacks.infrastructure.http.StacksSnapshotResolver
import rpcnode.toolkit.chains.stacks.infrastructure.start.StacksNodeStart
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseAsset
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class StacksTest
{
    /** `sample/conf/mainnet-follower-conf.toml` of stacks-core 4.0.5 (comments trimmed). */
    private val mainnet = """
[node]
# working_dir = "/stacks-data/mainnet"

rpc_bind = "0.0.0.0:20443"
p2p_bind = "0.0.0.0:20444"
prometheus_bind = "0.0.0.0:9153"

miner = false
stacker = false

[burnchain]
mode = "mainnet"
peer_host = "127.0.0.1"
# rpc_port = 8332  # Bitcoin mainnet RPC (default)
# username = "your-bitcoin-rpc-user"

# [connection_options]
# auth_token = "your-secret-token"
"""

    private val burn = StacksBurnchain("10.0.0.5", 18332, 18333, "rpc", "pa\"ss\\word")

    @Test
    fun mainnet_config_is_the_sample_with_start_keys_set()
    {
        val out = StacksConfigToml.render(mainnet, StacksChains.MAINNET, "/data/stacks", burn)
        assertTrue(out.contains("working_dir = \"/data/stacks\""), out)
        assertTrue(out.contains("rpc_bind = \"0.0.0.0:42500\""))
        assertTrue(out.contains("p2p_bind = \"0.0.0.0:42502\""))
        assertTrue(out.contains("prometheus_bind = \"127.0.0.1:42504\""), "metrics stay on loopback")
        assertTrue(out.contains("peer_host = \"10.0.0.5\""))
        assertTrue(out.contains("rpc_port = 18332"))
        assertTrue(out.contains("peer_port = 18333"))
        assertTrue(out.contains("username = \"rpc\""))
        assertTrue(out.contains("password = \"pa\\\"ss\\\\word\""), out)
        assertTrue(out.contains("max_http_clients = 3000"))
        assertTrue(out.contains("# auth_token = \"your-secret-token\""), "auth_token stays commented out")
        assertTrue(out.contains("mode = \"mainnet\"") && out.contains("miner = false"))
        // idempotent: a second Start changes nothing
        assertEquals(out, StacksConfigToml.render(out, StacksChains.MAINNET, "/data/stacks", burn))
    }

    @Test
    fun testnet_keeps_the_hosted_regtest_burnchain()
    {
        val sample = "[node]\nrpc_bind = \"0.0.0.0:20443\"\n[burnchain]\nmode = \"krypton\"\npeer_host = \"bitcoin.regtest.hiro.so\"\nrpc_port = 18443\n"
        val out = StacksConfigToml.render(sample, StacksChains.TESTNET, "/d", null)
        assertTrue(out.contains("peer_host = \"bitcoin.regtest.hiro.so\"") && out.contains("rpc_port = 18443"), out)
        assertTrue(out.contains("rpc_bind = \"0.0.0.0:42510\""))
    }

    @Test
    fun burnchain_input_is_validated()
    {
        assertNull(StacksConfigToml.validate(burn))
        assertTrue(StacksConfigToml.validate(burn.copy(host = "a b; rm")) != null)
        assertTrue(StacksConfigToml.validate(burn.copy(password = "x\ny")) != null)
        assertTrue(StacksConfigToml.validate(burn.copy(rpcPort = 0)) != null)
    }

    @Test
    fun catalog_equals_code()
    {
        for (env in StacksChains.ALL)
        {
            val ports = CatalogFixtures.ports("stacks", env.id)
            assertEquals(mapOf("http" to env.rpcPort, "p2p" to env.p2pPort, "metrics" to env.metricsPort), ports)
            assertEquals(env.publicTipUrls, CatalogFixtures.publicTip("stacks", env.id))
        }
        assertEquals(listOf("mainnet", "testnet"), CatalogFixtures.envIds("stacks"))
        val lit = CatalogFixtures.bindings("stacks").filter { it.source == "literal" }.associate { it.key to it.value }
        assertEquals(StacksSettings.MAX_HTTP_CLIENTS.toString(), lit["connection_options.max_http_clients"])
        assertEquals("1048576", lit["LimitNOFILE"])
        // the burnchain options exist on mainnet only
        assertTrue(CatalogFixtures.bindingsFor("stacks", "mainnet").any { it.option == "burnchain_password" })
        assertTrue(CatalogFixtures.bindingsFor("stacks", "testnet").none { it.option?.startsWith("burnchain") == true })
        val body = StacksUnitBodies.unit(StacksChains.MAINNET, "/n", "/n/bin/stacks-node", "/n/stacks.toml", "/n/logs/stacks.log")
        assertTrue(!body.contains("{{") && body.contains("start --config=/n/stacks.toml") && body.contains("TimeoutStopSec=180"), body)
        assertTrue(!body.contains("password"), "no credential in the unit")
    }

    @Test
    fun start_plan_passes_burnchain_options_only_on_mainnet()
    {
        fun ctx(env: String) = ChainNodeStartContext(
            NetworkId.STACKS, env, "stacks-node", null, nodeDir = "/data/stacks",
            installOptionsJson = """{"burnchain_host":"10.0.0.5","burnchain_user":"u","burnchain_password":"p"}""",
        )
        val main = StacksNodeStart().plan(ctx("mainnet")).launch.args
        assertTrue("--toolkit-burn-host=10.0.0.5" in main && "--toolkit-burn-rpc-port=8432" in main && "--toolkit-burn-p2p-port=8333" in main)
        assertTrue("--toolkit-burn-pass=p" in main && "--toolkit-burn-user=u" in main)
        val test = StacksNodeStart().plan(ctx("testnet")).launch.args
        assertTrue(test.none { it.startsWith("--toolkit-burn") })
        assertEquals("stacks_info", StacksNodeStart().plan(ctx("mainnet")).height.kind)
    }

    private fun gh(releases: List<GitHubRelease>) = object : GitHubReleaseClient
    {
        override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease? = null
        override suspend fun releases(repo: String, perPage: Int) = releases
    }

    @Test
    fun release_rule_skips_signer_tags_and_needs_the_zip() = runBlocking {
        val zip = listOf(GitHubReleaseAsset("linux-glibc-x64.zip", ""))
        val list = listOf(
            GitHubRelease("4.0.5", "4.0.5", zip),
            GitHubRelease("signer-3.4.0.0.4.0", "signer-3.4.0.0.4.0", zip),
            GitHubRelease("4.0.6", "4.0.6", emptyList()),
            GitHubRelease("3.4.0.0.4", "3.4.0.0.4", zip),
        )
        assertEquals("4.0.5", StacksClientReleaseResolver(gh(list)).resolve(EnvId.MAINNET)!!.version)
        assertEquals("4.0.5", StacksClientReleaseResolver(gh(list)).resolve(EnvId.TESTNET)!!.tag)
        assertNull(StacksClientReleaseResolver(gh(list)).resolve(EnvId.SEPOLIA))
        Unit
    }

    @Test
    fun snapshot_is_the_zstd_stream_with_a_ranged_size() = runBlocking {
        val resolver = StacksSnapshotResolver(size = { 274_700_000_000L })
        val main = resolver.resolve(EnvId.MAINNET, "full")!!
        assertEquals("https://archive.hiro.so/mainnet/stacks-blockchain/mainnet-stacks-blockchain-latest.tar.zst", main.url)
        assertTrue(main.streamUnpack)
        assertEquals(274_700_000_000L, main.sizeBytes)
        assertEquals("https://archive.hiro.so/testnet/stacks-blockchain/testnet-stacks-blockchain-latest.tar.zst", resolver.resolve(EnvId.TESTNET, "full")!!.url)
        assertEquals(274_700_000_000L, StacksSnapshotResolver.totalFromContentRange("bytes 0-0/274700000000"))
        assertNull(StacksSnapshotResolver.totalFromContentRange(null))
        assertTrue(main.url.endsWith(".tar.zst"), "the agent unpacks by suffix: .tar.zst -> zstd")
    }
}
