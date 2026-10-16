package rpcnode.toolkit.chains.arc.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository

class ArcUnitBodiesTest
{
    private fun script(env: String, mode: String) = ArcUnitBodies.runScript(
        env = env,
        mode = mode,
        execDir = "/data/arc/exec",
        consDir = "/data/arc/cons",
        binDir = "/data/arc/bin",
        stateDir = "/data/arc/.toolkit",
        logFile = "/data/arc/logs/arc.log",
        ports = ArcPortTable.forEnv(env),
    )

    @Test
    fun script_renders_without_placeholders_and_is_valid_bash()
    {
        val body = script("testnet", "full")
        assertFalse(body.contains("{{"))
        assertTrue(body.contains("CHAIN='arc-testnet'"))
        assertTrue(body.contains("HTTP_PORT=8592"))
        assertTrue(body.contains("RUN='/run/rpcnode-arc-testnet'"))
        assertTrue(body.contains("'https://rpc.testnet.arc.io,wss=rpc.testnet.arc.io'"))
        assertFalse(body.contains("quicknode"), "testnet has no quicknode relay")
        val file = Files.createTempFile("arc-run", ".sh")
        try
        {
            Files.writeString(file, body)
            val p = ProcessBuilder("bash", "-n", file.toString()).redirectErrorStream(true).start()
            assertEquals(0, p.waitFor(), p.inputStream.bufferedReader().readText())
        }
        finally
        {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun archive_downloads_the_archive_profile_and_never_prunes_while_full_uses_the_tutorial_path()
    {
        val archive = script("mainnet", "archive")
        assertTrue(archive.contains("MODE='archive'"))
        assertTrue(archive.contains("""[[ "${'$'}MODE" == archive ]] && snap_args+=(--el-profile archive)"""))
        assertTrue(archive.contains("""[[ "${'$'}MODE" == archive ]] || a+=(--full)"""), "--full only when not archive, for both layers")
        assertTrue(archive.contains("RELAYS=("))
        assertTrue(archive.contains("'https://rpc.quicknode.mainnet.arc.io,wss=rpc.quicknode.mainnet.arc.io'"))
        assertTrue(script("mainnet", "whatever").contains("MODE='full'"))
    }

    @Test
    fun the_snapshot_runs_before_the_consensus_key_and_both_are_one_time_steps()
    {
        val body = script("mainnet", "full")
        assertTrue(body.indexOf("arc-snapshots") < body.indexOf("arc-node-consensus\" init"))
        assertTrue(body.contains("arc-cl-init.done"))
        assertTrue(body.contains("arc-snapshot-\$MODE.done"))
        assertTrue(body.contains("--disable-discovery"))
    }

    @Test
    fun every_service_listener_is_on_all_interfaces_only_the_ipc_sockets_stay_local()
    {
        val body = script("mainnet", "full")
        assertTrue(body.contains("--metrics \"0.0.0.0:\$EL_METRICS\""))
        assertTrue(body.contains("--rpc.addr \"0.0.0.0:\$CL_RPC\""))
        assertTrue(body.contains("--metrics \"0.0.0.0:\$CL_METRICS\""))
        assertTrue(body.contains("HTTP_ADDR='0.0.0.0'"))
        assertFalse(body.contains("127.0.0.1"))
    }

    @Test
    fun unit_has_a_runtime_dir_for_the_ipc_sockets()
    {
        val unit = ArcUnitBodies.unit("testnet", "/n", "/n/bin/rpcnode-arc-run.sh", "/n/logs/arc.log")
        assertFalse(unit.contains("{{"))
        assertTrue(unit.contains("RuntimeDirectory=rpcnode-arc-testnet"))
        assertTrue(unit.contains("ExecStart=/n/bin/rpcnode-arc-run.sh"))
        assertTrue(unit.contains("LimitNOFILE=${ArcUnitBodies.NODE_NOFILE}"))
    }

    @Test
    fun catalog_ports_and_literals_match_the_code()
    {
        val programs = YamlClientProgramCatalog().all().filter { it.network == NetworkId.ARC }
        assertEquals(setOf("mainnet", "testnet"), programs.map { it.env.value }.toSet())
        for (p in programs)
        {
            val ports = ArcPortTable.forEnv(p.env.value)
            val byRole = p.ports.associate { it.role to it.port }
            assertEquals(ports.http, byRole["http"])
            assertEquals(ports.ws, byRole["ws"])
            assertEquals(ports.elMetrics, byRole["el_metrics"])
            assertEquals(ports.clRpc, byRole["cl_rpc"])
            assertEquals(ports.clMetrics, byRole["cl_metrics"])
        }
        val facts = YamlNetworkFactsRepository().factsFor(NetworkId.ARC)!!
        val literals = facts.clientConfig!!.bindings.filter { it.source == "literal" }.associate { it.path to it.value }
        assertEquals(ArcUnitBodies.HTTP_ADDR, literals["http.addr"])
        assertEquals(ArcUnitBodies.HTTP_API, literals["http.api"])
        assertEquals(ArcUnitBodies.RPC_MAX_CONNECTIONS.toString(), literals["rpc.max-connections"])
        assertEquals(ArcUnitBodies.RPC_MAX_SUBSCRIPTIONS.toString(), literals["rpc.max-subscriptions-per-connection"])
        assertEquals(ArcUnitBodies.BACKPRESSURE_THRESHOLD.toString(), literals["execution-persistence-backpressure-threshold"])
        assertEquals(ArcUnitBodies.NODE_NOFILE.toString(), literals["LimitNOFILE"])
        assertEquals(listOf("execution"), facts.diskRoles.map { it.id }, "both databases live under the one execution role")
        assertEquals("required", facts.envs.single { it.id == "testnet" }.snapshot)
        assertEquals(listOf("full", "archive"), facts.envs.single { it.id == "testnet" }.snapshotTypes.map { it.id })
        assertEquals("full", facts.envs.single { it.id == "mainnet" }.snapshotTypes.single { it.default }.id)
    }
}
