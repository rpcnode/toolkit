package rpcnode.toolkit.chains.avalanche.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.catalog.domain.NetworkId

class AvalancheUnitBodiesTest
{
    @Test
    fun full_is_pruned_with_state_sync_and_archive_turns_pruning_off()
    {
        val full = Json.parseToJsonElement(AvalancheUnitBodies.cChainConfig("full")).jsonObject
        assertTrue(full.getValue("pruning-enabled").jsonPrimitive.boolean)
        assertTrue(full.getValue("state-sync-enabled").jsonPrimitive.boolean)
        val archive = Json.parseToJsonElement(AvalancheUnitBodies.cChainConfig("archive")).jsonObject
        assertFalse(archive.getValue("pruning-enabled").jsonPrimitive.boolean)
        assertFalse(archive.getValue("state-sync-enabled").jsonPrimitive.boolean)
        assertEquals(50_000_000, archive.getValue("rpc-gas-cap").jsonPrimitive.content.toInt())
        assertEquals(100, archive.getValue("rpc-tx-fee-cap").jsonPrimitive.content.toInt())
        val apis = (archive.getValue("eth-apis") as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(AvalancheUnitBodies.ETH_APIS, apis)
        assertTrue("eth" in apis && "debug" in apis)
    }

    @Test
    fun command_line_carries_env_ports_and_dirs()
    {
        val args = AvalancheUnitBodies.args("fuji", "/n/data", "/chain/db", "/n/logs/avalanchego", 9660, 9661)
        assertTrue("--network-id=fuji" in args)
        assertTrue("--data-dir=/n/data" in args)
        assertTrue("--db-dir=/chain/db" in args)
        assertTrue("--log-dir=/n/logs/avalanchego" in args)
        assertTrue("--http-port=9660" in args)
        assertTrue("--staking-port=9661" in args)
        assertTrue("--http-allowed-hosts=*" in args)
        assertTrue("--api-admin-enabled=false" in args, "the node-control API stays off")
        assertTrue("--network-id=mainnet" in AvalancheUnitBodies.args("whatever", "/d", "/b", "/l", 1, 2))
    }

    @Test
    fun unit_renders_without_placeholders()
    {
        val unit = AvalancheUnitBodies.unit("mainnet", "/n", "/n/bin/avalanchego --network-id=mainnet", "/n/logs/avalanche.log")
        assertFalse(unit.contains("{{"))
        assertTrue(unit.contains("ExecStart=/n/bin/avalanchego --network-id=mainnet"))
        assertTrue(unit.contains("LimitNOFILE=${AvalancheUnitBodies.NODE_NOFILE}"))
    }

    @Test
    fun catalog_ports_and_capacity_bindings_match_the_code()
    {
        val programs = YamlClientProgramCatalog().all().filter { it.network == NetworkId.AVALANCHE }
        assertEquals(setOf("mainnet", "fuji"), programs.map { it.env.value }.toSet())
        for (p in programs)
        {
            val ports = AvalanchePortTable.forEnv(p.env.value)
            val byRole = p.ports.associate { it.role to it.port }
            assertEquals(ports.http, byRole["http"], "${p.env.value} http")
            assertEquals(ports.staking, byRole["p2p"], "${p.env.value} staking")
            assertEquals("avalanchego", p.programId)
        }
        val facts = YamlNetworkFactsRepository().factsFor(NetworkId.AVALANCHE)!!
        val literals = facts.clientConfig!!.bindings.filter { it.source == "literal" }.associate { it.path to it.value }
        assertEquals(AvalancheUnitBodies.HTTP_HOST, literals["http-host"])
        assertEquals(AvalancheUnitBodies.HTTP_ALLOWED_HOSTS, literals["http-allowed-hosts"])
        assertEquals(AvalancheUnitBodies.RPC_GAS_CAP.toString(), literals["rpc-gas-cap"])
        assertEquals(AvalancheUnitBodies.RPC_TX_FEE_CAP.toString(), literals["rpc-tx-fee-cap"])
        assertEquals(AvalancheUnitBodies.NODE_NOFILE.toString(), literals["LimitNOFILE"])
        assertEquals(AvalancheUnitBodies.ETH_APIS.joinToString(","), literals["eth-apis"])
        assertEquals("flags", facts.clientConfig!!.format)
        assertEquals(setOf("mainnet", "fuji"), facts.envs.map { it.id }.toSet())
    }
}
