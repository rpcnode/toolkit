package rpcnode.toolkit.chains.near

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.near.infrastructure.NearChains
import rpcnode.toolkit.chains.near.infrastructure.NearConfigPatch
import rpcnode.toolkit.chains.near.infrastructure.NearUnitBodies
import rpcnode.toolkit.chains.near.infrastructure.http.NearClientReleaseResolver
import rpcnode.toolkit.chains.near.infrastructure.start.NearNodeStart
import rpcnode.toolkit.chains.nonevm.infrastructure.CatalogFixtures
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class NearTest
{
    /** Shape of the live `network_info` of rpc.mainnet.near.org (2026-10-14), trimmed. */
    private val networkInfo = """{"jsonrpc":"2.0","result":{
      "active_peers":[
        {"account_id":null,"addr":"65.108.9.242:24567","id":"ed25519:HifYfeKaNBYXrbRgAck23PaLwyncRPKcitovhfEr3F8p"},
        {"account_id":null,"addr":"65.108.6.125:24567","id":"ed25519:EKcPNGueATx4QTrpzPfL1FouHH5U1MHZKsstH6eN6uxR"},
        {"account_id":null,"addr":null,"id":"ed25519:NoAddr"}],
      "known_producers":[
        {"account_id":"cunum.poolv1.near","addr":null,"peer_id":"ed25519:HifYfeKaNBYXrbRgAck23PaLwyncRPKcitovhfEr3F8p"},
        {"account_id":"mugglenodes.pool.near","addr":null,"peer_id":"ed25519:NoAddr"}],
      "num_active_peers":3,"peer_max_count":40},"id":"rpcnode"}"""

    private val config = """{"genesis_file":"genesis.json","rpc":{"addr":"0.0.0.0:3030","enable_debug_rpc":true,"limits_config":{"json_payload_max_size":10485760}},
      "network":{"addr":"0.0.0.0:24567","boot_nodes":"","max_num_peers":40},"view_client_threads":4,"gc_num_epochs_to_keep":5}"""

    @Test
    fun boot_nodes_are_active_peers_that_are_block_producers()
    {
        assertEquals(
            listOf("ed25519:HifYfeKaNBYXrbRgAck23PaLwyncRPKcitovhfEr3F8p@65.108.9.242:24567"),
            NearConfigPatch.bootNodes(networkInfo),
        )
        assertEquals(emptyList(), NearConfigPatch.bootNodes("""{"error":"x"}"""))
        assertEquals(emptyList(), NearConfigPatch.bootNodes(null))
    }

    @Test
    fun patch_sets_start_keys_and_keeps_the_rest()
    {
        val out = NearConfigPatch.apply(config, listOf("a@1.1.1.1:1", "b@2.2.2.2:2"))
        val root = Json.parseToJsonElement(out).jsonObject
        assertEquals("a@1.1.1.1:1,b@2.2.2.2:2", root["network"]!!.jsonObject["boot_nodes"]!!.jsonPrimitive.content)
        assertEquals("false", root["rpc"]!!.jsonObject["enable_debug_rpc"]!!.jsonPrimitive.content)
        assertEquals("16", root["view_client_threads"]!!.jsonPrimitive.content)
        assertEquals("10485760", root["rpc"]!!.jsonObject["limits_config"]!!.jsonObject["json_payload_max_size"]!!.jsonPrimitive.content)
        assertEquals("5", root["gc_num_epochs_to_keep"]!!.jsonPrimitive.content)
        assertEquals("0.0.0.0:24567", root["network"]!!.jsonObject["addr"]!!.jsonPrimitive.content)
        // an empty discovery keeps the boot nodes already there
        val kept = NearConfigPatch.apply(out, emptyList())
        assertEquals("a@1.1.1.1:1,b@2.2.2.2:2", NearConfigPatch.currentBootNodes(kept))
        assertEquals(out, kept, "the patch is idempotent")
    }

    @Test
    fun flags_and_literals_equal_the_catalog()
    {
        for (env in NearChains.ALL)
        {
            val flags = CatalogFixtures.flagMap(NearUnitBodies.flags(env, "/h"))
            val ports = CatalogFixtures.ports("near", env.id)
            assertEquals(ports["http"].toString(), flags["rpc-addr"]!!.substringAfterLast(':'))
            assertEquals(ports["p2p"].toString(), flags["network-addr"]!!.substringAfterLast(':'))
            assertEquals(ports["metrics"].toString(), flags["rpc-prometheus-addr"]!!.substringAfterLast(':'))
            assertTrue(flags["rpc-prometheus-addr"]!!.startsWith("127.0.0.1:"), "Prometheus stays on loopback")
            assertEquals(env.publicRpcUrls, CatalogFixtures.publicTip("near", env.id))
        }
        assertEquals(NearChains.ALL.map { it.id }, CatalogFixtures.envIds("near"))
        val lit = CatalogFixtures.bindings("near").filter { it.source == "literal" }.associate { it.key to it.value }
        assertEquals("false", lit["rpc.enable_debug_rpc"])
        assertEquals("16", lit["view_client_threads"])
        assertEquals("1048576", lit["LimitNOFILE"])
        val body = NearUnitBodies.unit(NearChains.MAINNET, "/n", "/n/bin/neard", "/n", "/n/logs/near.log")
        assertTrue(!body.contains("{{") && body.contains("TimeoutStopSec=300") && body.contains("--home=/n run"), body)
        assertEquals(
            listOf("/n/bin/neard", "--home", "/h", "init", "--chain-id", "mainnet", "--download-genesis", "--download-config", "rpc"),
            NearUnitBodies.initCommand("/n/bin/neard", NearChains.MAINNET, "/h"),
        )
    }

    @Test
    fun start_plan()
    {
        val plan = NearNodeStart().plan(
            ChainNodeStartContext(NetworkId.NEAR, "testnet", "neard", null, nodeDir = "/data/near"),
        )
        assertEquals("bin/neard", plan.launch.entry)
        assertEquals("near_status", plan.height.kind)
        assertTrue("--toolkit-env=testnet" in plan.launch.args)
    }

    private fun gh(vararg tags: Pair<String, Boolean>) = object : GitHubReleaseClient
    {
        override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease? = null
        override suspend fun releases(repo: String, perPage: Int) = tags.map { (t, pre) -> GitHubRelease(t, t, prerelease = pre) }
    }

    @Test
    fun release_rule_mainnet_final_testnet_with_rc_and_the_object_must_exist() = runBlocking {
        val releases = gh("2.14.0-rc.3" to true, "2.14.0-rc.2" to true, "2.13.4" to false, "2.13.3" to false)
        assertEquals("2.13.4", NearClientReleaseResolver(releases, { true }).resolve(EnvId.MAINNET)!!.version)
        assertEquals("2.14.0-rc.3", NearClientReleaseResolver(releases, { true }).resolve(EnvId.TESTNET)!!.version)
        // rc.3 is tagged but not uploaded yet: the next one is taken
        val urls = mutableListOf<String>()
        val got = NearClientReleaseResolver(releases, { u -> urls += u; !u.contains("2.14.0-rc.3") }).resolve(EnvId.TESTNET)
        assertEquals("2.14.0-rc.2", got!!.version)
        assertEquals("https://s3-us-west-1.amazonaws.com/build.nearprotocol.com/nearcore/Linux-x86_64/2.14.0-rc.3/neard", urls.first())
        assertNull(NearClientReleaseResolver(releases, { false }).resolve(EnvId.MAINNET))
        assertNull(NearClientReleaseResolver(releases, { true }).resolve(EnvId.SEPOLIA))
        Unit
    }
}
