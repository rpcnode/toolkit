package rpcnode.toolkit.chains.aptos.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository

class AptosUnitBodiesTest
{
    private fun yaml(env: String, mode: String, overrides: Map<String, String> = emptyMap()) =
        AptosUnitBodies.fullnodeYaml(
            env, "/data/rpcnode/aptos/$env/state", "/n/genesis.blob", "/n/waypoint.txt", AptosPortTable.forEnv(env), mode, overrides,
        )

    @Test
    fun mainnet_fast_yaml_has_the_catalog_ports_and_vendor_limits()
    {
        val y = yaml("mainnet", "fast")
        for (line in listOf(
            "role: \"full_node\"", "data_dir: \"/data/rpcnode/aptos/mainnet/state\"", "from_file: \"/n/waypoint.txt\"",
            "genesis_file_location: \"/n/genesis.blob\"", "address: \"0.0.0.0:41060\"", "runtime_worker_multiplier: 2",
            "max_gas_view_function: 2000000", "wait_by_hash_max_active_connections: 100", "content_length_limit: 8388608",
            "listen_address: \"/ip4/0.0.0.0/tcp/41062\"", "max_inbound_connections: 50", "max_outbound_connections: 5",
            "port: 41064", "enabled: false", "port: 41067", "bootstrapping_mode: DownloadLatestStates",
            "backup_service_address: \"127.0.0.1:41068\"",
        ))
        {
            assertTrue(line in y, "missing $line in\n$y")
        }
        assertFalse("storage_pruner_config" in y)
    }

    @Test
    fun genesis_mode_turns_the_pruners_off_and_testnet_uses_its_block()
    {
        val y = yaml("testnet", "genesis")
        assertTrue("bootstrapping_mode: ExecuteOrApplyFromGenesis" in y)
        assertEquals(3, Regex("      enable: false").findAll(y).count())
        assertTrue("0.0.0.0:41070" in y && "tcp/41072" in y && "port: 41074" in y && "127.0.0.1:41078" in y)
    }

    @Test
    fun only_allowlisted_overrides_with_valid_values_apply()
    {
        val y = yaml(
            "mainnet", "fast",
            mapOf("api.runtime_worker_multiplier" to "4", "p2p.max_outbound_connections" to "10", "api.address" to "1.2.3.4:1", "api.max_gas_view_function" to "x"),
        )
        assertTrue("runtime_worker_multiplier: 4" in y && "max_outbound_connections: 10" in y)
        assertFalse("1.2.3.4" in y)
        assertTrue("max_gas_view_function: 2000000" in y)
    }

    @Test
    fun unit_renders()
    {
        val u = AptosUnitBodies.unit("mainnet", "/n", AptosUnitBodies.execStart("/n/bin/aptos-node", "/n/fullnode.yaml"), "/n/logs/aptos.log")
        assertFalse("{{" in u)
        assertTrue("ExecStart=/n/bin/aptos-node -f /n/fullnode.yaml" in u && "LimitNOFILE=1048576" in u)
        assertTrue("StandardOutput=append:/n/logs/aptos.log" in u)
    }

    @Test
    fun catalog_ports_and_bindings_match_the_code()
    {
        val programs = YamlClientProgramCatalog().all().filter { it.network == NetworkId.APTOS }
        assertEquals(setOf("mainnet", "testnet"), programs.map { it.env.value }.toSet())
        for (p in programs)
        {
            val ports = AptosPortTable.forEnv(p.env.value)
            val byRole = p.ports.associate { it.role to it.port }
            assertEquals(ports.http, byRole["http"])
            assertEquals(ports.p2p, byRole["p2p"])
            assertEquals(ports.metrics, byRole["metrics"])
            assertEquals(ports.admin, byRole["admin"])
            assertEquals(ports.backup, byRole["backup"])
            assertEquals("aptos-node", p.programId)
        }
        val facts = YamlNetworkFactsRepository().factsFor(NetworkId.APTOS)!!
        assertEquals("flags", facts.clientConfig!!.format)
        assertEquals("aptos-node", facts.clientConfig!!.program)
        assertTrue(facts.envs.all { it.snapshot == "never" })
        assertEquals(setOf("mainnet", "testnet"), facts.envs.map { it.id }.toSet())
    }
}
