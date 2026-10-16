package rpcnode.toolkit.chains.opstack.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.yaml.snakeyaml.Yaml
import rpcnode.toolkit.clients.domain.model.ClientVersionSource
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository

/** What the panel shows (network.yml bindings / tip lag, clients.yml ports and pins) must be what the node runs. */
class OpstackCatalogTest
{
    private val facts = YamlNetworkFactsRepository()
    private val programs = YamlClientProgramCatalog().all()

    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing $path" }.readBytes().toString(Charsets.UTF_8)

    @Suppress("UNCHECKED_CAST")
    private fun yaml(path: String): Map<String, Any?> = Yaml().load<Any?>(resource(path)) as Map<String, Any?>

    @Test
    fun the_family_ships_exactly_the_networks_the_scope_names()
    {
        assertEquals(
            listOf("unichain", "ink", "soneium", "worldchain", "lisk", "mode", "zora"),
            OpstackNetworks.ALL.map { it.network.value },
        )
        // lisk is mainnet only (Sepolia RPC dead + needs a patched op-node); the rest ship both envs
        for (spec in OpstackNetworks.ALL)
        {
            val expected = if (spec.network.value == "lisk") listOf("mainnet") else listOf("mainnet", "sepolia")
            assertEquals(expected, spec.envs.map { it.id }, spec.network.value)
            assertEquals(expected, facts.factsFor(spec.network)!!.envs.map { it.id }, "network.yml envs of ${spec.network.value}")
        }
    }

    @Test
    fun literal_bindings_equal_what_the_script_applies()
    {
        for (spec in OpstackNetworks.ALL)
        {
            val net = facts.factsFor(spec.network)!!
            val literals = net.clientConfig!!.bindings.filter { it.source == "literal" }.associate { it.path to it.value }
            assertEquals(OpstackUnitBodies.HTTP_ADDR, literals["http.addr"], spec.network.value)
            assertEquals(OpstackUnitBodies.HTTP_API, literals["http.api"], spec.network.value)
            assertEquals(OpstackUnitBodies.LOOPBACK, literals["authrpc.addr"], spec.network.value)
            assertEquals(OpstackUnitBodies.LOOPBACK, literals["metrics.addr"], spec.network.value)
            assertEquals(OpstackUnitBodies.LOOPBACK, literals["rpc.addr"], spec.network.value)
            assertEquals("true", literals["ipcdisable"], spec.network.value)
            assertEquals("true", literals["rollup.disable-tx-pool-gossip"], spec.network.value)
            assertEquals(OpstackUnitBodies.L1_CONFS.toString(), literals["verifier.l1-confs"], spec.network.value)
            assertEquals(OpstackUnitBodies.NODE_NOFILE.toString(), literals["LimitNOFILE"], spec.network.value)
            // prune window and sync mode are per env in code: the one binding must hold for every env of the network
            for (env in spec.envs)
            {
                assertEquals(env.pruneDistance.toString(), literals["prune.distance"], "${spec.network.value}/${env.id} prune.distance")
                assertEquals(env.syncMode.flag, literals["syncmode"], "${spec.network.value}/${env.id} syncmode")
            }
            val applied = (literals.keys + net.clientConfig!!.bindings.filter { it.source != "literal" }.map { it.path }).toSet()
            for (key in listOf("node", "restore", "snapshot-url", "datadir", "consensus-dir", "snapshots", "discovery.v5.port", "l1-rpc", "l1-beacon"))
            {
                assertTrue(key in applied, "${spec.network.value}: binding $key missing")
            }
            assertEquals(listOf("execution", "consensus", "snapshots"), net.diskRoles.map { it.id })
            assertEquals(spec.elFlavor.binary, net.clientConfig!!.program)
        }
    }

    @Test
    fun the_narrow_public_api_never_serves_debug_trace_txpool_or_admin()
    {
        val namespaces = OpstackUnitBodies.HTTP_API.split(',').toSet()
        assertEquals(setOf("eth", "net", "web3"), namespaces)
    }

    @Test
    fun ports_in_clients_yml_equal_the_spec()
    {
        for (spec in OpstackNetworks.ALL)
        {
            val mine = programs.filter { it.network == spec.network }
            assertEquals(spec.envs.map { it.id }.toSet(), mine.map { it.env.value }.toSet(), spec.network.value)
            for (env in spec.envs)
            {
                val byRole = mine.filter { it.env.value == env.id }.flatMap { it.ports }.associate { it.role to it.port }
                val p = env.ports
                assertEquals(p.http, byRole["http"], "${spec.network.value}/${env.id} http")
                assertEquals(p.ws, byRole["ws"], "ws")
                assertEquals(p.p2p, byRole["p2p"], "p2p")
                assertEquals(p.engine, byRole["engine"], "engine")
                assertEquals(p.metrics, byRole["metrics"], "metrics")
                assertEquals(p.clRpc, byRole["cl_rpc"], "cl_rpc")
                assertEquals(p.clP2p, byRole["cl_p2p"], "cl_p2p")
                assertEquals(p.clMetrics, byRole["cl_metrics"], "cl_metrics")
                assertEquals(p.discv5, byRole["discv5"], "discv5")
                // the op-reth discv5 UDP port must never be the reth default every other node on the host would also take
                assertTrue(p.discv5 != 9200 && p.discv5 != p.p2p)
            }
        }
    }

    @Test
    fun the_panel_builds_exactly_the_tags_the_run_script_pins()
    {
        for (spec in OpstackNetworks.ALL)
        {
            for (p in programs.filter { it.network == spec.network })
            {
                when (p.programId)
                {
                    "op-reth", "op-node" ->
                    {
                        val pinned = p.source as ClientVersionSource.Pinned
                        val expected = if (p.programId == "op-reth") OpstackToolchain.RETH_TAG else OpstackToolchain.NODE_TAG
                        assertEquals(expected, pinned.tag, "${spec.network.value}/${p.env.value}/${p.programId}")
                        assertEquals(expected.substringAfter("/v"), pinned.version)
                        assertEquals(listOf(p.programId), p.build?.outputs)
                        val dockerfile = javaClass.classLoader.getResource("chains/${spec.network.value}/${p.build!!.dockerfile}")
                        assertNotNull(dockerfile, "${spec.network.value}: ${p.build!!.dockerfile} beside clients.yml")
                        if (p.programId == "op-node")
                        {
                            assertTrue(dockerfile.readText().contains("ARG GO_VERSION=${OpstackToolchain.GO_VERSION}"), "Go toolchain differs from the run script")
                        }
                    }
                    "world-chain" -> assertEquals(OpstackElFlavor.WORLD_CHAIN, spec.elFlavor)
                    else -> error("unexpected program ${p.programId}")
                }
                assertEquals(spec.relativeLog, p.requirements.logFile, "${spec.network.value}/${p.programId} log")
            }
            assertEquals(
                spec.elFlavor.binary,
                programs.first { it.network == spec.network && it.programId != "op-node" }.programId,
                "${spec.network.value} execution client",
            )
        }
    }

    @Test
    fun the_toolchain_pins_equal_optimisms_so_the_family_adds_no_new_build_recipe()
    {
        assertEquals("op-reth/v2.6.0", OpstackToolchain.RETH_TAG)
        assertEquals("op-node/v1.19.9", OpstackToolchain.NODE_TAG)
    }

    @Test
    fun world_chain_artifacts_are_the_release_tarballs_per_cpu()
    {
        val specs = programs.filter { it.network.value == "worldchain" && it.programId == "world-chain" }
        assertEquals(2, specs.size)
        for (spec in specs)
        {
            assertTrue(spec.build == null, "a release binary is not compiled")
            val a = spec.artifacts.single()
            assertEquals("world-chain-x86_64.tar.gz", a.name)
            assertEquals("world-chain-aarch64.tar.gz", a.nameAarch64)
            assertEquals(
                "https://github.com/worldcoin/world-chain/releases/download/v2.4.5/world-chain-v2.4.5-x86_64-unknown-linux-gnu.tar.gz",
                a.urlTemplate.replace("{tag}", "v2.4.5"),
            )
            assertEquals(
                "https://github.com/worldcoin/world-chain/releases/download/v2.4.5/world-chain-v2.4.5-aarch64-unknown-linux-gnu.tar.gz",
                a.urlTemplateAarch64!!.replace("{tag}", "v2.4.5"),
            )
            val src = spec.source as ClientVersionSource.GitHubRelease
            assertEquals("worldcoin/world-chain", src.repo)
            assertEquals("v", src.tagPrefix)
        }
    }

    @Test
    fun one_second_chains_get_a_wider_tip_lag_than_the_default()
    {
        val lag = { net: String, env: String -> facts.factsFor(rpcnode.toolkit.catalog.domain.NetworkId.parse(net)!!)!!.envs.first { it.id == env }.tipLagBlocks }
        for (net in listOf("unichain", "ink"))
        {
            for (env in listOf("mainnet", "sepolia"))
            {
                assertEquals(12L, lag(net, env), "$net/$env: 12 one-second blocks = one L1 slot")
            }
        }
        // 2 s chains stay at the default (3 blocks = 6 s)
        for (net in listOf("soneium", "worldchain", "lisk", "mode", "zora"))
        {
            assertEquals(null, lag(net, "mainnet"), net)
        }
    }

    @Test
    fun public_tips_exist_and_every_env_derives_from_the_matching_ethereum_network()
    {
        for (spec in OpstackNetworks.ALL)
        {
            for (env in facts.factsFor(spec.network)!!.envs)
            {
                assertTrue(env.publicTipUrls.isNotEmpty(), "${spec.network.value}/${env.id} publicTip")
                val sepolia = env.id == "sepolia"
                assertEquals(sepolia, env.l1RpcUrl!!.contains("sepolia"), "${spec.network.value}/${env.id} L1 rpc")
                assertEquals(sepolia, env.l1BeaconUrl!!.contains("sepolia"), "${spec.network.value}/${env.id} L1 beacon")
                assertEquals("never", env.snapshot, "the run script owns the restore")
            }
        }
    }

    @Test
    fun third_party_snapshots_are_labelled_as_such_in_the_catalog()
    {
        for (net in listOf("unichain", "ink", "soneium"))
        {
            val notes = facts.factsFor(rpcnode.toolkit.catalog.domain.NetworkId.parse(net)!!)!!.diskNotes.joinToString("\n")
            assertTrue(notes.contains("THIRD PARTY") && notes.contains("no checksum"), "$net must say the snapshot is third-party with no checksum")
        }
        val lisk = facts.factsFor(rpcnode.toolkit.catalog.domain.NetworkId.LISK)!!.diskNotes.joinToString("\n")
        assertTrue(lisk.contains("OFFICIAL") && lisk.contains("sha256"))
        for (net in listOf("mode", "zora"))
        {
            val notes = facts.factsFor(rpcnode.toolkit.catalog.domain.NetworkId.parse(net)!!)!!.diskNotes.joinToString("\n")
            assertTrue(notes.contains("no public snapshot") && notes.contains("snapshot_url") && notes.contains("weeks"), net)
        }
    }

    @Test
    fun registry_chain_names_are_the_ones_compiled_into_op_reth_and_op_node()
    {
        // op-reth key_for!(name, env): mainnet -> name, else name-env; op-node --network: <registry file>-<superchain>
        val expected = mapOf(
            "unichain" to listOf("unichain" to "unichain-mainnet", "unichain-sepolia" to "unichain-sepolia"),
            "ink" to listOf("ink" to "ink-mainnet", "ink-sepolia" to "ink-sepolia"),
            "soneium" to listOf("soneium" to "soneium-mainnet", "soneium-minato-sepolia" to "soneium-minato-sepolia"),
            "worldchain" to listOf("worldchain" to "worldchain-mainnet", "worldchain-sepolia" to "worldchain-sepolia"),
            "lisk" to listOf("lisk" to "lisk-mainnet"),
            "mode" to listOf("mode" to "mode-mainnet", "mode-sepolia" to "mode-sepolia"),
            "zora" to listOf("zora" to "zora-mainnet", "zora-sepolia" to "zora-sepolia"),
        )
        for (spec in OpstackNetworks.ALL)
        {
            assertEquals(expected[spec.network.value], spec.envs.map { it.rethChain to it.nodeNetwork }, spec.network.value)
        }
    }

    @Test
    fun the_clients_yml_header_documents_the_pins_in_a_yaml_that_parses()
    {
        for (spec in OpstackNetworks.ALL)
        {
            assertNotNull(yaml("chains/${spec.network.value}/clients.yml")["programs"])
            assertNotNull(yaml("chains/${spec.network.value}/network.yml")["envs"])
        }
    }
}
