package rpcnode.toolkit.chains.substrate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.nonevm.infrastructure.CatalogFixtures
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateFlags
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateMode
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateSpecs
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateUnitBodies
import rpcnode.toolkit.chains.substrate.infrastructure.start.SubstrateNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.catalog.domain.NetworkId

/** What Start's Config preview shows (network.yml / clients.yml) must be what the unit runs. */
class SubstrateCatalogTest
{
    private val spec = SubstrateSpecs.POLKADOT
    private val kindOf = mapOf(SubstrateMode.PRUNED to "full", SubstrateMode.ARCHIVE to "archive")

    @Test
    fun bindings_equal_the_flags_of_every_env_and_mode()
    {
        val problems = mutableListOf<String>()
        for (env in spec.envs)
        {
            val ports = CatalogFixtures.ports("polkadot", env.id)
            for (mode in SubstrateMode.entries)
            {
                val flags = CatalogFixtures.flagMap(SubstrateFlags.relayChain(spec, env, mode, "/data"))
                val bindings = CatalogFixtures.bindingsFor("polkadot", env.id).filter { it.whenOption == null }
                for (b in bindings)
                {
                    when (b.source)
                    {
                        "literal" -> if (b.key != "LimitNOFILE" && flags[b.key] != b.value)
                            problems += "${env.id}/${mode.id}: ${b.key} binding=${b.value} flag=${flags[b.key]}"
                        "catalog_port" -> if (flags[b.key] != ports[b.role].toString())
                            problems += "${env.id}/${mode.id}: ${b.key} binding port ${ports[b.role]} flag=${flags[b.key]}"
                        "snapshot_kind" -> if (flags[b.key] != b.map[kindOf[mode]])
                            problems += "${env.id}/${mode.id}: ${b.key} binding=${b.map[kindOf[mode]]} flag=${flags[b.key]}"
                        "disk_role_dir" -> if (flags[b.key] != "/data") problems += "${env.id}: ${b.key} flag=${flags[b.key]}"
                    }
                }
                val bound = bindings.map { it.key }.toSet()
                for ((k, v) in flags)
                {
                    if (k !in bound) problems += "${env.id}/${mode.id}: flag --$k=$v has no binding"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun ports_and_envs_equal_the_spec()
    {
        assertEquals(spec.envs.map { it.id }, CatalogFixtures.envIds("polkadot"))
        for (env in spec.envs)
        {
            val p = spec.ports(env)
            val yml = CatalogFixtures.ports("polkadot", env.id)
            assertEquals(
                mapOf(
                    "http" to p.rpc, "p2p" to p.p2p, "metrics" to p.metrics, "indexer" to p.indexer,
                    "history_api" to p.historyApi, "history_metrics" to p.historyMetrics,
                ),
                yml,
                env.id,
            )
        }
    }

    @Test
    fun a_public_node_never_exposes_the_unsafe_surface()
    {
        for (env in spec.envs) for (mode in SubstrateMode.entries)
        {
            val flags = SubstrateFlags.relayChain(spec, env, mode, "/data")
            assertTrue("--rpc-methods=safe" in flags)
            assertTrue(flags.none { it.contains("unsafe") || it.contains("prometheus-external") || it.startsWith("--validator") }, flags.toString())
        }
    }

    @Test
    fun archive_uses_the_database_of_the_snapshot()
    {
        fun db(env: String, mode: SubstrateMode) = SubstrateFlags.modeFlags(spec.env(env), mode).first()
        assertEquals("--database=paritydb", db("mainnet", SubstrateMode.PRUNED))
        assertEquals("--database=rocksdb", db("mainnet", SubstrateMode.ARCHIVE))
        assertEquals("--database=rocksdb", db("paseo", SubstrateMode.ARCHIVE))
        assertEquals("--database=paritydb", db("westend", SubstrateMode.ARCHIVE))
        assertEquals(listOf("--sync=full", "--state-pruning=archive", "--blocks-pruning=archive"), SubstrateFlags.modeFlags(spec.env("mainnet"), SubstrateMode.ARCHIVE).drop(1))
    }

    @Test
    fun unit_renders_without_placeholders()
    {
        val env = spec.env("mainnet")
        val exec = SubstrateUnitBodies.execStart("/n/bin/polkadot", SubstrateFlags.relayChain(spec, env, SubstrateMode.PRUNED, "/n"))
        val body = SubstrateUnitBodies.unit(spec, env, "/n", exec, "/n/logs/polkadot.log")
        assertTrue(!body.contains("{{"), body)
        assertTrue(body.contains("LimitNOFILE=1048576"))
        assertTrue(body.contains("TimeoutStopSec=120"))
        assertTrue(body.contains("ExecStart=/n/bin/polkadot \\\n  --chain=polkadot"), body)
    }

    @Test
    fun start_plan_carries_env_mode_state_and_indexer()
    {
        val plan = SubstrateNodeStart(spec).plan(
            ChainNodeStartContext(
                network = NetworkId.POLKADOT, env = "westend", program = "polkadot", configFile = null, nodeDir = "/data/polkadot",
                installOptionsJson = """{"snapshot":"archive","indexer":"rest-api"}""",
            ),
        )
        assertEquals("bin/polkadot", plan.launch.entry)
        assertEquals("substrate_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertTrue("--toolkit-env=westend" in plan.launch.args)
        assertTrue("--toolkit-mode=archive" in plan.launch.args)
        assertTrue("--toolkit-state=/data/polkadot" in plan.launch.args)
        assertTrue("--toolkit-indexer=rest-api" in plan.launch.args)
        val def = SubstrateNodeStart(spec).plan(
            ChainNodeStartContext(network = NetworkId.POLKADOT, env = "mainnet", program = "polkadot", configFile = null, nodeDir = "/d"),
        )
        assertTrue("--toolkit-mode=pruned" in def.launch.args)
        assertNotNull(def.launch.logFile)
    }
}
