package rpcnode.toolkit.networks.infrastructure.facts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId

class YamlNetworkFactsRepositoryTest
{
    @Test
    fun loads_all_yaml_files_on_the_classpath_at_construction()
    {
        val repo = YamlNetworkFactsRepository()
        val bitcoin = repo.factsFor(NetworkId.BITCOIN)
        assertNotNull(bitcoin)
        assertEquals("Bitcoin", bitcoin.label)
        assertEquals(4, bitcoin.envs.size)
        assertEquals(2, bitcoin.diskRoles.size)
        assertEquals("blockchain", bitcoin.diskRoles[0].id)
        assertEquals("ssd", bitcoin.diskRoles[0].media)
        assertTrue(bitcoin.diskNotes.isNotEmpty())
        assertEquals(false, bitcoin.oneEnvPerHost)
        assertNull(bitcoin.diskMedia)

        val mainnet = bitcoin.envs.single { it.id == "mainnet" }
        assertEquals("Bitcoin Mainnet", mainnet.label)
        assertEquals(1024.0, mainnet.diskHintGiB)
        assertEquals(1024.0, mainnet.fullNodeGiB)
        assertEquals(4.0, mainnet.cpuCores)
        assertEquals(16.0, mainnet.memoryGiB)
        assertEquals("never", mainnet.snapshot)
    }

    @Test
    fun loads_tron_facts_with_the_mainnet_snapshot_mirror_split()
    {
        val repo = YamlNetworkFactsRepository()
        val tron = repo.factsFor(NetworkId.TRON)
        assertNotNull(tron)
        assertEquals("TRON", tron.label)
        assertEquals(3, tron.envs.size)
        assertEquals(2, tron.diskRoles.size)
        assertEquals("fullnode", tron.diskRoles[0].id)
        assertEquals("nvme", tron.diskRoles[0].media)

        val mainnet = tron.envs.single { it.id == "mainnet" }
        assertEquals(4096.0, mainnet.diskHintGiB)
        assertEquals(2900.0, mainnet.fullNodeGiB)
        assertEquals(3600.0, mainnet.archiveGiB)
        assertEquals(8.0, mainnet.cpuCores)
        assertEquals(32.0, mainnet.memoryGiB)
        assertEquals("required", mainnet.snapshot)

        val shasta = tron.envs.single { it.id == "shasta" }
        assertEquals("never", shasta.snapshot)
        assertNull(shasta.archiveGiB)
    }

    @Test
    fun returns_null_for_a_network_this_install_ships_no_facts_for()
    {
        val repo = YamlNetworkFactsRepository()
        val unknown = NetworkId.parse("does-not-exist")!!
        assertNull(repo.factsFor(unknown))
        assertNull(repo.find(unknown))
    }

    @Test
    fun catalog_is_the_yaml_files_sorted_by_id()
    {
        val repo = YamlNetworkFactsRepository()
        val ids = repo.all().map { it.id.value }
        assertEquals(
            listOf("arb", "base", "bch", "bitcoin", "bsc", "dash", "doge", "ethereum", "hyperliquid", "ltc", "polygon", "solana", "sui", "ton", "tron", "xrpl", "zcash"),
            ids,
        )

        val bitcoin = repo.find(NetworkId.BITCOIN)
        assertNotNull(bitcoin)
        assertEquals("Bitcoin", bitcoin.label)
        assertEquals(
            setOf("mainnet", "testnet4", "signet", "regtest"),
            bitcoin.envs.map { it.id.value }.toSet(),
        )

        val tron = repo.find(NetworkId.TRON)
        assertNotNull(tron)
        assertEquals("TRON", tron.label)
        assertEquals(setOf("mainnet", "nile", "shasta"), tron.envs.map { it.id.value }.toSet())

        val eth = repo.find(NetworkId.ETHEREUM)
        assertNotNull(eth)
        assertEquals("Ethereum", eth.label)
        assertEquals(setOf("mainnet", "sepolia", "hoodi"), eth.envs.map { it.id.value }.toSet())
        val ethFacts = repo.factsFor(NetworkId.ETHEREUM)
        assertNotNull(ethFacts)
        assertEquals("flags", ethFacts.clientConfig?.format)
        assertEquals("never", ethFacts.envs.single { it.id == "mainnet" }.snapshot)

        val sol = repo.find(NetworkId.SOLANA)
        assertNotNull(sol)
        assertEquals("Solana", sol.label)
        assertEquals(setOf("mainnet", "testnet", "devnet"), sol.envs.map { it.id.value }.toSet())
        val solFacts = repo.factsFor(NetworkId.SOLANA)
        assertNotNull(solFacts)
        assertEquals("flags", solFacts.clientConfig?.format)
        assertEquals(
            setOf(
                "ledger",
                "accounts",
                "snapshots",
                "rpc-port",
                "p2p-port",
                "rpc-threads",
                "rpc-pubsub-worker-threads",
                "rpc-pubsub-max-active-subscriptions",
                "rpc-max-request-body-size",
                "LimitNOFILE",
                "net.core.rmem_default",
                "net.core.rmem_max",
                "net.core.wmem_default",
                "net.core.wmem_max",
                "vm.max_map_count",
                "fs.nr_open",
            ),
            solFacts.clientConfig!!.bindings.map { it.path }.toSet(),
        )
        assertEquals("required", solFacts.envs.single { it.id == "mainnet" }.snapshot)
        assertEquals("via_node", solFacts.envs.single { it.id == "mainnet" }.snapshotBootstrap)
        assertEquals(3, solFacts.diskRoles.size)

        val poly = repo.find(NetworkId.POLYGON)
        assertNotNull(poly)
        assertEquals("Polygon", poly.label)
        assertEquals(setOf("mainnet", "amoy"), poly.envs.map { it.id.value }.toSet())
        val polyFacts = repo.factsFor(NetworkId.POLYGON)
        assertNotNull(polyFacts)
        assertEquals("flags", polyFacts.clientConfig?.format)
        assertEquals(
            setOf("node", "datadir", "heimdall-home", "http-port", "p2p-port", "heimdall-api-port"),
            polyFacts.clientConfig!!.bindings.map { it.path }.toSet(),
        )
        assertEquals("never", polyFacts.envs.single { it.id == "mainnet" }.snapshot)
        assertEquals(2, polyFacts.diskRoles.size)

        val bsc = repo.find(NetworkId.BSC)
        assertNotNull(bsc)
        assertEquals("BNB Smart Chain", bsc.label)
        assertEquals(setOf("mainnet", "testnet"), bsc.envs.map { it.id.value }.toSet())
        val bscFacts = repo.factsFor(NetworkId.BSC)
        assertNotNull(bscFacts)
        assertEquals("flags", bscFacts.clientConfig?.format)
        assertEquals("required", bscFacts.envs.single { it.id == "mainnet" }.snapshot)
        assertEquals(2, bscFacts.diskRoles.size)
        assertEquals("pruned", bscFacts.envs.single { it.id == "mainnet" }.snapshotTypes.single { it.default }.id)

        val base = repo.find(NetworkId.BASE)
        assertNotNull(base)
        assertEquals("Base", base.label)
        assertEquals(setOf("mainnet", "sepolia"), base.envs.map { it.id.value }.toSet())
        val baseFacts = repo.factsFor(NetworkId.BASE)
        assertNotNull(baseFacts)
        assertEquals("flags", baseFacts.clientConfig?.format)
        assertEquals("required", baseFacts.envs.single { it.id == "mainnet" }.snapshot)
        assertEquals(2, baseFacts.diskRoles.size)
        assertEquals("archive", baseFacts.envs.single { it.id == "mainnet" }.snapshotTypes.single { it.default }.id)
        assertEquals(
            "https://ethereum-rpc.publicnode.com",
            baseFacts.envs.single { it.id == "mainnet" }.l1RpcUrl,
        )
        assertEquals(
            "https://ethereum-sepolia-rpc.publicnode.com",
            baseFacts.envs.single { it.id == "sepolia" }.l1RpcUrl,
        )
        assertTrue(baseFacts.clientConfig!!.bindings.any { it.option == "l1_rpc" })
        assertTrue(baseFacts.clientConfig!!.bindings.any { it.option == "l1_beacon" })
        val l1Rpc = baseFacts.clientConfig!!.bindings.single { it.option == "l1_rpc" }
        assertEquals("eth_rpc", l1Rpc.testConnect?.kind)
        assertEquals("Test connect", l1Rpc.testConnect?.label)
        assertTrue(l1Rpc.testConnect?.help?.contains("eth_blockNumber") == true)
        val l1Beacon = baseFacts.clientConfig!!.bindings.single { it.option == "l1_beacon" }
        assertEquals("beacon_genesis", l1Beacon.testConnect?.kind)

        val arb = repo.find(NetworkId.ARB)
        assertNotNull(arb)
        assertEquals("Arbitrum", arb.label)
        assertEquals("arbitrum", arb.root())
        assertEquals(setOf("mainnet", "sepolia"), arb.envs.map { it.id.value }.toSet())
        val arbFacts = repo.factsFor(NetworkId.ARB)
        assertNotNull(arbFacts)
        assertEquals("flags", arbFacts.clientConfig?.format)
        assertEquals("never", arbFacts.envs.single { it.id == "mainnet" }.snapshot)
        assertEquals(2, arbFacts.diskRoles.size)
        assertEquals(
            setOf("snapshot", "datadir", "snapshots", "http-port", "ws-port", "LimitNOFILE", "l1-rpc", "l1-beacon"),
            arbFacts.clientConfig!!.bindings.map { it.path }.toSet(),
        )
        assertEquals(
            "https://ethereum-rpc.publicnode.com",
            arbFacts.envs.single { it.id == "mainnet" }.l1RpcUrl,
        )

        val sui = repo.find(NetworkId.SUI)
        assertNotNull(sui)
        assertEquals("Sui", sui.label)
        assertEquals(setOf("mainnet", "testnet"), sui.envs.map { it.id.value }.toSet())
        val suiFacts = repo.factsFor(NetworkId.SUI)
        assertNotNull(suiFacts)
        assertEquals("flags", suiFacts.clientConfig?.format)
        assertEquals("required", suiFacts.envs.single { it.id == "mainnet" }.snapshot)
        assertEquals(2, suiFacts.diskRoles.size)
        assertEquals("state", suiFacts.diskRoles[0].id)
        assertEquals("formal", suiFacts.envs.single { it.id == "mainnet" }.snapshotTypes.single { it.default }.id)
        assertEquals(
            setOf(
                "db-path",
                "index",
                "json-rpc-address",
                "metrics-address",
                "p2p-listen",
                "enable-event-processing",
                "num-epochs-to-retain",
                "archive-concurrency",
                "LimitNOFILE",
            ),
            suiFacts.clientConfig!!.bindings.map { it.path }.toSet(),
        )

        val xrpl = repo.find(NetworkId.XRPL)
        assertNotNull(xrpl)
        assertEquals("XRP Ledger", xrpl.label)
        assertEquals(setOf("mainnet", "testnet"), xrpl.envs.map { it.id.value }.toSet())
        val xrplFacts = repo.factsFor(NetworkId.XRPL)
        assertNotNull(xrplFacts)
        assertEquals("flags", xrplFacts.clientConfig?.format)
        assertEquals("never", xrplFacts.envs.single { it.id == "mainnet" }.snapshot)
        assertEquals(1, xrplFacts.diskRoles.size)
        assertEquals("ledger", xrplFacts.diskRoles[0].id)
        assertEquals(
            setOf(
                "ledger",
                "http-port",
                "p2p-port",
                "ws-port",
                "grpc-port",
                "xrpl_history",
                "peers_max",
                "LimitNOFILE",
            ),
            xrplFacts.clientConfig!!.bindings.map { it.path }.toSet(),
        )
    }
}
