package rpcnode.toolkit.chains.optimism.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository

class OptimismUnitBodiesTest
{
    private fun script(env: String, mode: String) = OptimismUnitBodies.runScript(
        env = env,
        mode = mode,
        nodeDir = "/data/op",
        execDir = "/data/op/exec",
        consDir = "/data/op/cons",
        snapDir = "/data/op/snaps",
        stateDir = "/data/op/.toolkit",
        logFile = "/data/op/logs/optimism.log",
        l1Rpc = "https://l1.example",
        l1Beacon = "https://beacon.example",
        ports = OptimismPortTable.forEnv(env),
    )

    @Test
    fun script_renders_without_placeholders_and_is_valid_bash()
    {
        val body = script("mainnet", "full")
        assertFalse(body.contains("{{"))
        assertTrue(body.contains("RETH_CHAIN='optimism'"))
        assertTrue(body.contains("NODE_NETWORK='op-mainnet'"))
        assertTrue(body.contains("SEQUENCER='https://mainnet-sequencer.optimism.io'"))
        assertTrue(body.contains("HTTP_PORT=8561"))
        assertTrue(body.contains(":-${OptimismUnitBodies.RETH_TAG}}"))
        assertTrue(body.contains(":-${OptimismUnitBodies.NODE_TAG}}"))
        val file = Files.createTempFile("op-run", ".sh")
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
    fun the_snapshot_is_mandatory_on_mainnet_and_only_for_archive_on_sepolia()
    {
        assertTrue(script("mainnet", "full").contains("SNAP_REQUIRED=1"), "pre-Bedrock state import")
        assertTrue(script("sepolia", "archive").contains("SNAP_REQUIRED=1"))
        assertTrue(script("sepolia", "full").contains("SNAP_REQUIRED=0"))
        assertTrue(script("sepolia", "full").contains("RETH_CHAIN='optimism_sepolia'"))
        assertTrue(script("sepolia", "full").contains("NODE_NETWORK='op-sepolia'"))
        assertTrue(script("sepolia", "full").contains("SNAP_NET='sepolia'"))
    }

    @Test
    fun archive_never_prunes_and_full_prunes_state_but_not_block_bodies()
    {
        val body = script("mainnet", "archive")
        assertTrue(body.contains("MODE='archive'"))
        assertTrue(body.contains("""if [[ "${'$'}MODE" != archive ]]; then"""))
        assertTrue(body.contains("--prune.receipts.distance"))
        assertTrue(body.contains("--prune.account-history.distance"))
        assertTrue(body.contains("--prune.storage-history.distance"))
        assertFalse(body.contains("--prune.bodies"), "block bodies are never pruned: op-node reads them")
        assertFalse(body.contains("--minimal"), "op-reth rejects --minimal")
        assertTrue(body.contains("--syncmode=execution-layer"))
        assertTrue(body.contains("--l2.enginekind=reth"))
        assertTrue(body.contains("PRUNE_DISTANCE=1296000"))
    }

    @Test
    fun only_the_json_rpc_is_on_all_interfaces_engine_metrics_and_op_node_rpc_stay_on_loopback()
    {
        val body = script("mainnet", "full")
        assertTrue(body.contains("--metrics=\"\$LOOPBACK:\$METRICS_PORT\""))
        assertTrue(body.contains("--rpc.addr=\"\$LOOPBACK\" --rpc.port=\"\$CL_RPC\""))
        assertTrue(body.contains("--metrics.addr=\"\$LOOPBACK\""))
        assertTrue(body.contains("--authrpc.addr=\"\$LOOPBACK\""), "the JWT-guarded Engine API stays local like Base and Ethereum")
        assertTrue(body.contains("LOOPBACK='127.0.0.1'"))
        assertTrue(body.contains("--ipcdisable"), "reth's default IPC socket is shared by every op-reth of the host")
        assertTrue(body.contains("--discovery.v5.port=\"\$V5_PORT\""))
        assertFalse(body.contains("0.0.0.0:\$"), "no listener but the JSON-RPC may bind all interfaces")
    }

    @Test
    fun snapshots_are_the_v2_storage_files_checked_with_sha256()
    {
        val body = script("mainnet", "full")
        assertTrue(body.contains("mainnet-op-\${SNAP_NET}-reth-\${kind}-storage-v2-"))
        assertTrue(body.contains("--checksum=\"sha-256=\$sha\""))
        assertTrue(body.contains("tar -I zstd -xf"))
        assertTrue(body.contains("SNAP_HOST='datadirs.optimism.io'"))
    }

    @Test
    fun builds_both_binaries_from_the_pinned_tags_without_docker()
    {
        val body = script("mainnet", "full")
        assertFalse(body.contains("docker", ignoreCase = true))
        assertTrue(body.contains("cargo build --release --bin op-reth -p op-reth"))
        assertTrue(body.contains("./op-node/cmd"))
        assertEquals("op-reth/v2.6.0", OptimismUnitBodies.RETH_TAG)
        assertEquals("op-node/v1.19.9", OptimismUnitBodies.NODE_TAG)
    }

    @Test
    fun unit_renders()
    {
        val unit = OptimismUnitBodies.unit("sepolia", "/n", "/n/bin/rpcnode-optimism-run.sh", "/n/logs/optimism.log")
        assertFalse(unit.contains("{{"))
        assertTrue(unit.contains("ExecStart=/n/bin/rpcnode-optimism-run.sh"))
        assertTrue(unit.contains("LimitNOFILE=${OptimismUnitBodies.NODE_NOFILE}"))
    }

    @Test
    fun the_panel_builds_exactly_the_tags_the_run_script_pins()
    {
        val programs = YamlClientProgramCatalog().all().filter { it.network == NetworkId.OPTIMISM }
        for (spec in programs)
        {
            val pinned = spec.source as rpcnode.toolkit.clients.domain.model.ClientVersionSource.Pinned
            val expected = if (spec.programId == "op-reth") OptimismUnitBodies.RETH_TAG else OptimismUnitBodies.NODE_TAG
            assertEquals(expected, pinned.tag, "${spec.env.value}/${spec.programId}")
            assertEquals(expected.substringAfter("/v"), pinned.version)
            assertEquals(listOf(spec.programId), spec.build?.outputs, "a ${spec.programId} build is declared")
        }
        val nodeDockerfile = javaClass.classLoader.getResource("chains/optimism/op-node.Dockerfile")!!.readText()
        assertTrue(nodeDockerfile.contains("ARG GO_VERSION=${OptimismUnitBodies.GO_VERSION}"), "Go toolchain differs from the run script")
    }

    @Test
    fun a_prebuilt_binary_is_preferred_and_the_host_build_is_the_fallback()
    {
        val s = script("sepolia", "full")
        assertTrue(s.contains("use_prebuilt()"))
        assertTrue(s.contains("\$ROOT/\$1-\$(uname -m)"))
        assertTrue(s.contains("does not run on this host — building from source"))
        assertTrue(s.contains("sync-superchain.sh"), "op-node needs superchain-configs.zip generated before go build")
    }

    @Test
    fun catalog_ports_and_literals_match_the_code()
    {
        val programs = YamlClientProgramCatalog().all().filter { it.network == NetworkId.OPTIMISM }
        assertEquals(setOf("mainnet", "sepolia"), programs.map { it.env.value }.toSet())
        for (env in listOf("mainnet", "sepolia"))
        {
            val ports = OptimismPortTable.forEnv(env)
            val byRole = programs.filter { it.env.value == env }.flatMap { it.ports }.associate { it.role to it.port }
            assertEquals(ports.http, byRole["http"], "$env http")
            assertEquals(ports.ws, byRole["ws"])
            assertEquals(ports.engine, byRole["engine"])
            assertEquals(ports.metrics, byRole["metrics"])
            assertEquals(ports.p2p, byRole["p2p"])
            assertEquals(ports.clRpc, byRole["cl_rpc"])
            assertEquals(ports.clMetrics, byRole["cl_metrics"])
            assertEquals(ports.clP2p, byRole["cl_p2p"])
            assertEquals(ports.discV5, byRole["discv5"])
        }
        val facts = YamlNetworkFactsRepository().factsFor(NetworkId.OPTIMISM)!!
        val literals = facts.clientConfig!!.bindings.filter { it.source == "literal" }.associate { it.path to it.value }
        assertEquals(OptimismUnitBodies.HTTP_ADDR, literals["http.addr"])
        assertEquals(OptimismUnitBodies.HTTP_API, literals["http.api"])
        assertEquals(OptimismUnitBodies.PRUNE_DISTANCE.toString(), literals["prune.distance"])
        assertEquals(OptimismUnitBodies.NODE_NOFILE.toString(), literals["LimitNOFILE"])
        assertEquals(listOf("execution", "consensus", "snapshots"), facts.diskRoles.map { it.id })
    }
}
