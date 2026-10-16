package rpcnode.toolkit.chains.robinhood.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository

class RobinhoodUnitBodiesTest
{
    private fun unit(env: String, snapshot: String) = RobinhoodUnitBodies.nitro(
        env = env,
        bin = "/n/bin/nitro",
        datadir = "/data/exec",
        chainInfo = "/n/robinhood-chain-info.json",
        l1Rpc = "https://l1.example",
        l1Beacon = "https://beacon.example",
        rpcPort = RobinhoodPortTable.forEnv(env).http,
        wsPort = RobinhoodPortTable.forEnv(env).ws,
        wasmRoots = "/n/a,/n/b",
        snapshot = snapshot,
        logFile = "/n/logs/node.out",
    )

    @Test
    fun pruned_mainnet_restores_the_latest_pruned_snapshot_from_the_robinhood_mirror()
    {
        val body = unit("mainnet", "pruned")
        assertFalse(body.contains("{{"))
        assertTrue(body.contains("--chain.info-files=/n/robinhood-chain-info.json"))
        assertTrue(body.contains("--init.latest=pruned"))
        assertTrue(body.contains("--init.latest-base=https://robinhood-snapshots.offchainlabs.com/"))
        assertTrue(body.contains("--http.port=8667"))
        assertTrue(body.contains("--node.feed.input.url=wss://feed.mainnet.chain.robinhood.com"))
        assertTrue(body.contains("--node.feed.input.url=wss://delayed-feed.mainnet.chain.robinhood.com"))
        assertFalse(body.contains("--execution.caching.archive"))
        assertFalse(body.contains("--chain.id"), "the chain-info file replaces --chain.id")
        assertFalse(body.contains("--init.genesis-json-file"), "the snapshot carries the genesis")
        // Nitro exits with "ForwardingTarget not set and not sequencer" without it
        assertTrue(body.contains("--execution.forwarding-target=https://rpc.mainnet.chain.robinhood.com"))
    }

    @Test
    fun archive_uses_path_scheme_on_mainnet_and_the_hash_scheme_on_the_testnet()
    {
        val main = unit("mainnet", "archive")
        assertTrue(main.contains("--init.latest=archive"))
        assertTrue(main.contains("--execution.caching.archive"))
        assertTrue(main.contains("--execution.caching.state-scheme=path"))
        val test = unit("testnet", "archive")
        assertTrue(test.contains("--execution.caching.archive"))
        assertFalse(test.contains("state-scheme"), "the testnet archive snapshot is hash-scheme")
        assertTrue(test.contains("--chain.info-files=/n/robinhood-chain-info.json"))
        assertTrue(test.contains("--node.feed.input.url=wss://feed.testnet.chain.robinhood.com"))
        assertTrue(test.contains("--http.port=8677"))
        assertTrue(test.contains("--execution.forwarding-target=https://rpc.testnet.chain.robinhood.com"))
    }

    @Test
    fun catalog_ports_configs_and_envs_match_the_code()
    {
        val programs = YamlClientProgramCatalog().all().filter { it.network == NetworkId.ROBINHOOD }
        assertEquals(setOf("mainnet", "testnet"), programs.map { it.env.value }.toSet())
        for (p in programs)
        {
            val ports = RobinhoodPortTable.forEnv(p.env.value)
            val byRole = p.ports.associate { it.role to it.port }
            assertEquals(ports.http, byRole["http"])
            assertEquals(ports.ws, byRole["ws"])
            assertEquals(listOf(RobinhoodClusters.lookup(p.env.value).chainInfoFile), p.configs.map { it.name })
        }
        val facts = YamlNetworkFactsRepository().factsFor(NetworkId.ROBINHOOD)!!
        assertEquals(setOf("mainnet", "testnet"), facts.envs.map { it.id }.toSet())
        val literals = facts.clientConfig!!.bindings.filter { it.source == "literal" }.associate { it.path to it.value }
        assertEquals(RobinhoodClusters.SNAPSHOT_BASE, literals["init-latest-base"])
        assertEquals(RobinhoodUnitBodies.LIMIT_NOFILE, literals["LimitNOFILE"])
    }
}
