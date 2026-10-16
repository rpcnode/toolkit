package rpcnode.toolkit.chains.xai.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.yaml.snakeyaml.Yaml

class XaiUnitBodiesTest
{
    private val unit = XaiUnitBodies.nitro(
        bin = "/n/bin/nitro",
        datadir = "/data/exec",
        chainInfo = "/n/.toolkit/xai-chain-info.json",
        envFile = "/n/.toolkit/xai-parent.env",
        ports = XaiPortTable.MAINNET,
        wasmRoots = "/n/a,/n/b",
        logFile = "/n/logs/node.out",
    )

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing $path" }.readBytes().toString(Charsets.UTF_8)

    @Suppress("UNCHECKED_CAST")
    private fun yaml(path: String): Map<String, Any?> = Yaml().load<Any?>(resource(path)) as Map<String, Any?>

    @Test
    fun unit_selects_xai_from_the_chain_info_file_and_syncs_through_arbitrum_one_with_anytrust()
    {
        assertFalse(unit.contains("{{"), "every placeholder is filled")
        assertTrue(unit.contains("--chain.info-files=/n/.toolkit/xai-chain-info.json"))
        assertTrue(unit.contains("--chain.name=Xai"))
        assertTrue(unit.contains("--node.feed.input.url=wss://xai-chain.net/feed"))
        // Nitro refuses to start without a forwarding target
        assertTrue(unit.contains("--execution.forwarding-target=https://xai-chain.net/rpc"))
        // AnyTrust through the non-deprecated v3.10+ flags; the helm README's sequencer-inbox-address / parent-chain-node-url no longer exist
        assertTrue(unit.contains("--node.da.anytrust.enable"))
        assertTrue(unit.contains("--node.da.anytrust.rest-aggregator.enable"))
        assertTrue(unit.contains("--node.da.anytrust.rest-aggregator.online-url-list=https://xai-chain.net/das-servers"))
        assertFalse(unit.contains("node.data-availability"))
        assertFalse(unit.contains("sequencer-inbox-address"))
        // an Orbit chain on Arbitrum One has no blob / beacon parent, and the chain id comes from the chain-info file
        assertFalse(unit.contains("beacon"))
        assertFalse(unit.contains("--chain.id"))
        assertFalse(unit.contains("--init."), "no snapshot exists: Nitro initialises from the genesis in the chain info")
    }

    @Test
    fun the_parent_rpc_is_read_from_the_0600_env_file_not_from_argv_or_the_unit()
    {
        assertTrue(unit.contains("EnvironmentFile=/n/.toolkit/xai-parent.env"))
        assertTrue(unit.contains("--conf.env-prefix=NITRO"))
        assertFalse(unit.contains("--parent-chain.connection.url"))
        assertFalse(unit.contains("arb1.arbitrum.io"))
        assertTrue(XaiUnitBodies.parentEnv("https://arb.example/v2/KEY").contains("NITRO_PARENT__CHAIN_CONNECTION_URL=\"https://arb.example/v2/KEY\""))
    }

    @Test
    fun public_surface_is_trimmed_and_admin_endpoints_stay_on_loopback()
    {
        assertTrue(unit.contains("--http.api=net,web3,eth"))
        assertTrue(unit.contains("--ws.api=net,web3,eth"))
        assertFalse(unit.contains("debug"))
        assertTrue(unit.contains("--http.port=42710"))
        assertTrue(unit.contains("--ws.port=42711"))
        assertTrue(unit.contains("--metrics-server.addr=127.0.0.1"))
        assertTrue(unit.contains("--metrics-server.port=42714"))
        assertTrue(unit.contains("--persistent.chain=/data/exec"))
        assertTrue(unit.contains("--validation.wasm.allowed-wasm-module-roots=/n/a,/n/b"))
        assertTrue(unit.contains("LimitNOFILE=1048576"))
    }

    @Test
    fun capacity_flags_in_the_unit_equal_the_network_yml_bindings()
    {
        @Suppress("UNCHECKED_CAST")
        val bindings = (yaml("chains/xai/network.yml")["clientConfig"] as Map<String, Any?>)["bindings"] as List<Map<String, Any?>>
        val literals = bindings.filter { it["source"] == "literal" }.associate { it["key"].toString() to it["value"].toString() }
        assertEquals(
            mapOf(
                "http-api" to XaiChain.HTTP_API,
                "ws-api" to XaiChain.WS_API,
                "rpc.max-batch-response-size" to XaiChain.RPC_MAX_BATCH_RESPONSE_SIZE,
                "execution.rpc.filter-log-cache-size" to XaiChain.FILTER_LOG_CACHE_SIZE,
                "persistent.handles" to XaiChain.PERSISTENT_HANDLES,
                "LimitNOFILE" to XaiChain.LIMIT_NOFILE,
            ),
            literals,
        )
        for ((key, value) in literals)
        {
            val flag = when (key)
            {
                "http-api" -> "--http.api=$value"
                "ws-api" -> "--ws.api=$value"
                "LimitNOFILE" -> "LimitNOFILE=$value"
                else -> "--$key=$value"
            }
            assertTrue(unit.contains(flag), "unit does not apply the binding $flag")
        }
    }

    @Test
    fun clients_yml_ports_and_network_yml_equal_the_code()
    {
        @Suppress("UNCHECKED_CAST")
        val program = (yaml("chains/xai/clients.yml")["programs"] as List<Map<String, Any?>>).single()
        assertEquals("mainnet", program["env"])
        @Suppress("UNCHECKED_CAST")
        val ports = (program["ports"] as List<Map<String, Any?>>).associate { it["role"].toString() to (it["port"] as Int) }
        assertEquals(mapOf("http" to 42710, "ws" to 42711, "metrics" to 42714), ports)
        assertEquals(XaiPortTable.MAINNET.http, ports["http"])
        assertEquals(XaiPortTable.MAINNET.ws, ports["ws"])
        assertEquals(XaiPortTable.MAINNET.metrics, ports["metrics"])

        val net = yaml("chains/xai/network.yml")
        @Suppress("UNCHECKED_CAST")
        val env = (net["envs"] as List<Map<String, Any?>>).single()
        assertEquals("mainnet", env["id"])
        assertEquals("never", env["snapshot"], "no Xai snapshot exists")
        assertEquals(30, env["tipLagBlocks"], "~2.2 s blocks: the default 3-block window would flap")
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf(XaiChain.FORWARDING_TARGET), (env["publicTip"] as Map<String, Any?>)["urls"])
        @Suppress("UNCHECKED_CAST")
        val l1 = env["l1Parent"] as Map<String, Any?>
        assertEquals(XaiChain.DEFAULT_PARENT_RPC, l1["rpc"])
        assertEquals("", l1["beacon"], "the parent is Arbitrum One: no beacon")
    }

    @Test
    fun chain_info_resource_matches_the_constants_and_the_inbox_the_das_is_checked_against()
    {
        @Suppress("UNCHECKED_CAST")
        val entry = (Yaml().load<Any?>(resource(XaiChain.CHAIN_INFO_RESOURCE)) as List<Map<String, Any?>>).single()
        assertEquals(XaiChain.CHAIN_ID, (entry["chain-id"] as Number).toLong())
        assertEquals(XaiChain.PARENT_CHAIN_ID, (entry["parent-chain-id"] as Number).toLong())
        assertEquals(XaiChain.CHAIN_NAME, entry["chain-name"])
        assertEquals(true, entry["parent-chain-is-arbitrum"])
        @Suppress("UNCHECKED_CAST")
        val arbitrum = (entry["chain-config"] as Map<String, Any?>)["arbitrum"] as Map<String, Any?>
        assertEquals(true, arbitrum["DataAvailabilityCommittee"], "AnyTrust chain")
        @Suppress("UNCHECKED_CAST")
        val rollup = entry["rollup"] as Map<String, Any?>
        assertEquals("0x995a9d3ca121D48d21087eDE20bc8acb2398c8B1", rollup["sequencer-inbox"])
        assertEquals("0xC47DacFbAa80Bd9D8112F4e8069482c2A3221336", rollup["rollup"])
        assertEquals(166757506, (rollup["deployed-at"] as Number).toLong())
    }
}
