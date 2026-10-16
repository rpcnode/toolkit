package rpcnode.toolkit.chains.starknet.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.yaml.snakeyaml.Yaml

class StarknetUnitBodiesTest
{
    private fun unit(env: String, prune: String?) = StarknetUnitBodies.juno(
        env = StarknetChain.lookup(env),
        bin = "/n/bin/juno",
        nodeDir = "/n",
        datadir = "/data/state",
        envFile = "/n/.toolkit/juno.env",
        pruneWindow = prune,
        logFile = "/n/logs/node.out",
    )

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing $path" }.readBytes().toString(Charsets.UTF_8)

    @Suppress("UNCHECKED_CAST")
    private fun yaml(path: String): Map<String, Any?> = Yaml().load<Any?>(resource(path)) as Map<String, Any?>

    @Test
    fun mainnet_unit_serves_rpc_publicly_and_keeps_metrics_on_loopback()
    {
        val body = unit("mainnet", null)
        assertFalse(body.contains("{{"))
        assertTrue(body.contains("--network=mainnet"))
        assertTrue(body.contains("--db-path=/data/state"))
        assertTrue(body.contains("--http-host=0.0.0.0"))
        assertTrue(body.contains("--http-port=42530"))
        assertTrue(body.contains("--ws-host=0.0.0.0"))
        assertTrue(body.contains("--ws-port=42531"))
        assertTrue(body.contains("--metrics-host=127.0.0.1"))
        assertTrue(body.contains("--metrics-port=42534"))
        assertTrue(body.contains("WorkingDirectory=/n"))
        // what must not be reachable: pprof, gRPC, p2p, the log-level update port, gateway keys
        for (off in listOf("pprof", "grpc", "p2p", "http-update", "gw-api-key", "disable-l1-verification", "cors"))
        {
            assertFalse(body.contains(off), "$off must stay off")
        }
    }

    @Test
    fun the_l1_websocket_comes_from_the_0600_env_file_never_from_argv()
    {
        val body = unit("sepolia", null)
        assertTrue(body.contains("EnvironmentFile=/n/.toolkit/juno.env"))
        assertFalse(body.contains("--eth-node"))
        assertFalse(body.contains("wss://"))
        assertTrue(StarknetParentChain.envBody("wss://eth.example/ws/v3/KEY").contains("JUNO_ETH_NODE=\"wss://eth.example/ws/v3/KEY\""))
    }

    @Test
    fun sepolia_uses_its_own_network_and_ports()
    {
        val body = unit("sepolia", null)
        assertTrue(body.contains("--network=sepolia"))
        assertTrue(body.contains("--http-port=42540"))
        assertTrue(body.contains("--ws-port=42541"))
        assertTrue(body.contains("--metrics-port=42544"))
    }

    @Test
    fun pruning_is_a_flag_only_when_a_window_is_given()
    {
        assertFalse(unit("mainnet", null).contains("prune"))
        assertTrue(unit("mainnet", "128").contains("--prune-mode=128"))
        assertTrue(unit("mainnet", "100000").contains("--log-level=info \\\n  --prune-mode=100000\nRestart=on-failure"))
    }

    @Test
    fun capacity_flags_in_the_unit_equal_the_network_yml_bindings()
    {
        @Suppress("UNCHECKED_CAST")
        val bindings = (yaml("chains/starknet/network.yml")["clientConfig"] as Map<String, Any?>)["bindings"] as List<Map<String, Any?>>
        val literals = bindings.filter { it["source"] == "literal" }.associate { it["key"].toString() to it["value"].toString() }
        assertEquals(
            mapOf(
                "db-cache-size" to StarknetChain.DB_CACHE_SIZE_MB,
                "rpc-max-block-scan" to StarknetChain.RPC_MAX_BLOCK_SCAN,
                "rpc-request-timeout" to StarknetChain.RPC_REQUEST_TIMEOUT,
                "rpc-max-concurrent-requests" to StarknetChain.RPC_MAX_CONCURRENT_REQUESTS,
                "rpc-max-request-queue" to StarknetChain.RPC_MAX_REQUEST_QUEUE,
                "LimitNOFILE" to StarknetChain.LIMIT_NOFILE,
            ),
            literals,
        )
        val body = unit("mainnet", null)
        for ((key, value) in literals)
        {
            val applied = if (key == "LimitNOFILE") "LimitNOFILE=$value" else "--$key=$value"
            assertTrue(body.contains(applied), "unit does not apply the binding $applied")
        }
    }

    @Test
    fun clients_yml_ports_snapshots_and_network_yml_equal_the_code()
    {
        @Suppress("UNCHECKED_CAST")
        val programs = yaml("chains/starknet/clients.yml")["programs"] as List<Map<String, Any?>>
        assertEquals(StarknetChain.ENVS.map { it.id }, programs.map { it["env"].toString() })
        for (env in StarknetChain.ENVS)
        {
            val program = programs.single { it["env"] == env.id }
            @Suppress("UNCHECKED_CAST")
            val ports = (program["ports"] as List<Map<String, Any?>>).associate { it["role"].toString() to (it["port"] as Int) }
            assertEquals(mapOf("http" to env.ports.http, "ws" to env.ports.ws, "metrics" to env.ports.metrics), ports, env.id)
        }
        @Suppress("UNCHECKED_CAST")
        val envs = yaml("chains/starknet/network.yml")["envs"] as List<Map<String, Any?>>
        assertEquals(StarknetChain.ENVS.map { it.id }, envs.map { it["id"].toString() })
        for (env in envs)
        {
            assertEquals("required", env["snapshot"])
            @Suppress("UNCHECKED_CAST")
            val l1 = env["l1Parent"] as Map<String, Any?>
            assertEquals(StarknetChain.lookup(env["id"].toString()).defaultEthNode, l1["rpc"])
            @Suppress("UNCHECKED_CAST")
            val types = (env["snapshotTypes"] as List<Map<String, Any?>>)
            assertEquals(listOf("pruned", "full"), types.map { it["id"].toString() })
            assertEquals(listOf(true), types.mapNotNull { it["default"] }.filterIsInstance<Boolean>())
        }
        @Suppress("UNCHECKED_CAST")
        val snaps = yaml("chains/starknet/clients.yml")["snapshots"] as Map<String, Map<String, Map<String, Any?>>>
        assertEquals("https://juno-snapshots.nethermind.io/files/mainnet-pruned/latest", snaps["mainnet"]!!["pruned"]!!["mirror"])
        assertEquals("https://juno-snapshots.nethermind.io/files/sepolia/latest", snaps["sepolia"]!!["full"]!!["mirror"])
    }

    @Test
    fun option_bindings_are_the_ones_the_start_plan_reads()
    {
        @Suppress("UNCHECKED_CAST")
        val bindings = (yaml("chains/starknet/network.yml")["clientConfig"] as Map<String, Any?>)["bindings"] as List<Map<String, Any?>>
        val options = bindings.filter { it["source"] == "install_option" }.map { it["option"].toString() }.toSet()
        assertEquals(setOf("snapshot", "prune_blocks", "l1_rpc"), options)
        val roles = bindings.filter { it["source"] == "disk_role_dir" }.map { it["role"].toString() }
        assertEquals(listOf("state"), roles)
    }
}
