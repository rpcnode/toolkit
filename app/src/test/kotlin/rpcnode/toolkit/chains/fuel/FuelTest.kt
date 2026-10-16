package rpcnode.toolkit.chains.fuel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.fuel.infrastructure.FuelChains
import rpcnode.toolkit.chains.fuel.infrastructure.FuelEnvFile
import rpcnode.toolkit.chains.fuel.infrastructure.FuelUnitBodies
import rpcnode.toolkit.chains.fuel.infrastructure.http.FuelClientReleaseResolver
import rpcnode.toolkit.chains.fuel.infrastructure.start.FuelNodeStart
import rpcnode.toolkit.chains.nonevm.infrastructure.CatalogFixtures
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseAsset
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class FuelTest
{
    @Test
    fun bindings_equal_the_flags_of_every_env()
    {
        val problems = mutableListOf<String>()
        for (env in FuelChains.ALL)
        {
            val flags = CatalogFixtures.flagMap(FuelUnitBodies.flags(env, "/data/db", "/n/chain"))
            val ports = CatalogFixtures.ports("fuel", env.id)
            val bindings = CatalogFixtures.bindingsFor("fuel", env.id)
            for (b in bindings)
            {
                when (b.source)
                {
                    "literal" -> if (b.key != "LimitNOFILE" && b.key != "snapshot" && flags[b.key] != b.value) problems += "${env.id}: ${b.key} binding=${b.value} flag=${flags[b.key]}"
                    "catalog_port" -> if (flags[b.key] != ports[b.role].toString()) problems += "${env.id}: ${b.key}"
                    "disk_role_dir" -> if (flags[b.key] != "/data/db") problems += "${env.id}: ${b.key}"
                }
            }
            val bound = bindings.map { it.key }.toSet()
            for ((k, v) in flags) if (k !in bound) problems += "${env.id}: flag --$k=$v has no binding"
            // the secrets are never flags
            assertFalse("keypair" in flags || "relayer" in flags, "secrets come from the env file")
            assertEquals("false", flags["poa-instant"], "upstream defaults poa-instant to TRUE")
            assertFalse("debug" in flags || "rpc-port" in flags)
            assertEquals(mapOf("http" to env.graphqlPort, "p2p" to env.p2pPort), ports)
            assertEquals(env.publicTipUrls, CatalogFixtures.publicTip("fuel", env.id))
        }
        assertEquals(listOf("mainnet", "testnet"), CatalogFixtures.envIds("fuel"))
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun unit_reads_secrets_from_the_env_file()
    {
        val body = FuelUnitBodies.unit(FuelChains.MAINNET, "/n", "/n/bin/fuel-core", "/n/db", "/n/logs/fuel.log")
        assertTrue(!body.contains("{{"), body)
        assertTrue(body.contains("EnvironmentFile=/n/.toolkit/fuel.env"))
        assertTrue(body.contains("ExecStart=/n/bin/fuel-core run \\\n  --service-name=rpcnode-fuel"), body)
        assertTrue(body.contains("--snapshot=/n/chain") && body.contains("TimeoutStopSec=180"))
    }

    @Test
    fun env_file_and_keygen_output()
    {
        val keygen = """{"secret":"${"ab".repeat(32)}","peer_id":"16Uiu2HAm","type":"p2p"}"""
        assertEquals("ab".repeat(32), FuelEnvFile.secretFromKeygen(keygen))
        assertNull(FuelEnvFile.secretFromKeygen("""{"secret":"short"}"""))
        assertEquals("KEYPAIR=${"ab".repeat(32)}\nRELAYER=https://eth.example/v1/KEY\n", FuelEnvFile.render("ab".repeat(32), " https://eth.example/v1/KEY "))
        assertTrue(FuelEnvFile.validUrl("https://ethereum-rpc.publicnode.com"))
        assertFalse(FuelEnvFile.validUrl("https://x.example/a b"))
        assertFalse(FuelEnvFile.validUrl("https://x.example/\"; rm"))
        assertFalse(FuelEnvFile.validUrl("ws://x"))
        assertFalse(FuelEnvFile.validSecret("zz"))
    }

    @Test
    fun start_plan_hands_the_l1_rpc_to_the_host()
    {
        val plan = FuelNodeStart().plan(
            ChainNodeStartContext(NetworkId.FUEL, "testnet", "fuel-core", null, nodeDir = "/data/fuel", installOptionsJson = """{"l1_rpc":"https://sepolia.example/k"}"""),
        )
        assertTrue("--toolkit-relayer=https://sepolia.example/k" in plan.launch.args)
        assertEquals("fuel_graphql", plan.height.kind)
        assertEquals("bin/fuel-core", plan.launch.entry)
    }

    @Test
    fun release_rule_by_version_with_both_tarballs() = runBlocking {
        fun rel(tag: String, full: Boolean = true): GitHubRelease
        {
            val v = tag.removePrefix("v")
            val assets = if (full) FuelClientReleaseResolver.assets(v) else emptyList()
            return GitHubRelease(tag, v, assets.map { GitHubReleaseAsset(it, "") }, prerelease = !full)
        }
        val list = listOf(rel("testnet-v0.49.0", full = false), rel("v0.47.4"), rel("v0.48.3"), rel("v0.48.0"))
        val client = object : GitHubReleaseClient
        {
            override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease? = null
            override suspend fun releases(repo: String, perPage: Int) = list
        }
        val got = FuelClientReleaseResolver(client).resolve(EnvId.MAINNET)!!
        assertEquals("v0.48.3", got.tag)
        assertEquals("0.48.3", got.version)
        Unit
    }
}
