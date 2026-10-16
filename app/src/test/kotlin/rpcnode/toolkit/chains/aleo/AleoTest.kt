package rpcnode.toolkit.chains.aleo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.aleo.infrastructure.AleoChains
import rpcnode.toolkit.chains.aleo.infrastructure.AleoUnitBodies
import rpcnode.toolkit.chains.aleo.infrastructure.http.AleoClientReleaseResolver
import rpcnode.toolkit.chains.aleo.infrastructure.start.AleoNodeStart
import rpcnode.toolkit.chains.nonevm.infrastructure.CatalogFixtures
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseAsset
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class AleoTest
{
    @Test
    fun bindings_equal_the_flags()
    {
        val flags = CatalogFixtures.flagMap(AleoUnitBodies.flags("/l", "/nd", "/n/logs/snarkos.log"))
        val ports = CatalogFixtures.ports("aleo", "mainnet")
        val problems = mutableListOf<String>()
        val bindings = CatalogFixtures.bindingsFor("aleo", "mainnet")
        for (b in bindings)
        {
            when (b.source)
            {
                "literal" -> if (b.key == "logfile") assertTrue(flags["logfile"]!!.endsWith(b.value!!)) else if (b.key != "LimitNOFILE" && b.key != "client" && flags[b.key] != b.value && !(b.value == "true" && flags[b.key] == "true")) problems += "${b.key} binding=${b.value} flag=${flags[b.key]}"
                "catalog_port" -> if (flags[b.key]!!.substringAfterLast(':') != ports[b.role].toString()) problems += "${b.key} port"
                "disk_role_dir" -> if (flags[b.key] !in setOf("/l", "/nd")) problems += "${b.key}"
            }
        }
        for (k in flags.keys) if (bindings.none { it.key == k }) problems += "flag --$k has no binding"
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        assertEquals(mapOf("http" to 41030, "p2p" to 41032, "metrics" to 41034), ports)
        assertEquals(listOf("mainnet"), CatalogFixtures.envIds("aleo"))
        assertEquals(AleoChains.PUBLIC_TIP_URLS, CatalogFixtures.publicTip("aleo", "mainnet"))
        assertTrue(flags["metrics-ip"]!!.startsWith("127.0.0.1:"))
        assertFalse("nojwt" in flags, "JWT guards /db_backup and the mapping routes")
        assertFalse("private-key" in flags || "private-key-file" in flags)
        assertEquals("1000", flags["rest-rps"])
    }

    @Test
    fun unit_has_a_separate_stdout_file()
    {
        val body = AleoUnitBodies.unit("/n", "/n/bin/snarkos", "/l", "/nd", "/n/logs/snarkos.log", "/n/logs/snarkos.stdout")
        assertTrue(!body.contains("{{"), body)
        assertTrue(body.contains("ExecStart=/n/bin/snarkos start \\\n  --network=0"), body)
        assertTrue(body.contains("StandardOutput=append:/n/logs/snarkos.stdout"))
        assertTrue(body.contains("TimeoutStopSec=120"))
    }

    @Test
    fun start_plan_and_release_rule() = runBlocking {
        val plan = AleoNodeStart().plan(ChainNodeStartContext(NetworkId.ALEO, "mainnet", "snarkos", null, nodeDir = "/data/aleo"))
        assertEquals("aleo_rest", plan.height.kind)
        assertEquals("bin/snarkos", plan.launch.entry)

        fun rel(tag: String, vararg assets: String, pre: Boolean = false) = GitHubRelease(tag, tag.removePrefix("v"), assets.map { GitHubReleaseAsset(it, "") }, prerelease = pre)
        val list = listOf(
            rel("testnet-v4.12.0", pre = true),
            rel("v4.12.0", "aleo-v4.12.0-x86_64-pc-windows-msvc.zip"),
            rel("v4.11.0", "aleo-v4.11.0-x86_64-unknown-linux-gnu.zip", "aleo-v4.11.0-x86_64-pc-windows-msvc.zip"),
        )
        val client = object : GitHubReleaseClient
        {
            override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease? = null
            override suspend fun releases(repo: String, perPage: Int) = list
        }
        // v4.12.0 (as listed here) has no Linux zip yet: the newest one that has is taken; the testnet line is never resolved
        assertEquals("v4.11.0", AleoClientReleaseResolver(client).resolve(EnvId.MAINNET)!!.tag)
        assertNull(AleoClientReleaseResolver(client).resolve(EnvId.TESTNET))
        Unit
    }
}
