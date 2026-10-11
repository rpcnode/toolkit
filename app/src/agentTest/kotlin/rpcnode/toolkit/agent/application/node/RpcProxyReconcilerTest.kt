package rpcnode.toolkit.agent.application.node

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.agent.domain.model.RunningNode
import rpcnode.toolkit.agent.infrastructure.proxy.CatalogPublicRpcPort
import rpcnode.toolkit.agent.infrastructure.proxy.NodeRpcCredentials

class RpcProxyReconcilerTest
{
    private class MemoryRegistry : RunningNodeRegistry
    {
        val nodes = linkedMapOf<String, RunningNode>()
        override fun upsert(node: RunningNode) { nodes[node.nodeId] = node }
        override fun remove(nodeId: String) { nodes.remove(nodeId) }
        override fun get(nodeId: String): RunningNode? = nodes[nodeId]
        override fun list(): List<RunningNode> = nodes.values.toList()
    }

    private class Fake
    {
        val started = mutableListOf<Triple<Int, Int, String>>()
        val stopped = mutableListOf<Int>()
        var failOn: Int? = null

        fun create(listen: Int, target: Int, node: RunningNode): RpcProxyReconciler.StartedProxy
        {
            if (listen == failOn) error("Address already in use")
            started += Triple(listen, target, node.nodeId)
            return RpcProxyReconciler.StartedProxy(object : RunningRpcProxy { override fun stop() { stopped += listen } })
        }
    }

    private fun node(id: String, network: String, env: String, http: Int, dir: String = "/data/$id") =
        RunningNode(nodeId = id, network = network, env = env, nodeDir = dir, httpPort = http, pid = 1)

    private fun reconciler(registry: RunningNodeRegistry, fake: Fake) = RpcProxyReconciler(
        registry = registry,
        publicPort = { n, e -> CatalogPublicRpcPort.find(n, e) },
        credentials = { "u:p" },
        create = fake::create,
    )

    @Test
    fun the_catalog_gives_every_bitcoind_node_its_own_public_port()
    {
        assertEquals(8432, CatalogPublicRpcPort.find("bitcoin", "mainnet"))
        assertEquals(18432, CatalogPublicRpcPort.find("bitcoin", "testnet4"))
        assertEquals(9432, CatalogPublicRpcPort.find("ltc", "mainnet"))
        assertEquals(22655, CatalogPublicRpcPort.find("doge", "mainnet"))
        assertEquals(10098, CatalogPublicRpcPort.find("dash", "mainnet"))
        assertEquals(8433, CatalogPublicRpcPort.find("bch", "mainnet"))
        assertNull(CatalogPublicRpcPort.find("ethereum", "mainnet"), "geth has no login, no proxy")
        assertNull(CatalogPublicRpcPort.find("bitcoin", "nope"))
    }

    @Test
    fun public_ports_are_unique_across_the_whole_family()
    {
        val ports = listOf("bitcoin", "ltc", "doge", "dash", "bch").flatMap { net ->
            listOf("mainnet", "testnet", "testnet4", "signet", "regtest").mapNotNull { CatalogPublicRpcPort.find(net, it) }
        }
        assertEquals(ports.size, ports.toSet().size, "a public RPC port is shared: $ports")
        assertEquals(16, ports.size)
    }

    @Test
    fun a_bitcoind_node_gets_a_proxy_and_other_networks_do_not()
    {
        val registry = MemoryRegistry()
        registry.upsert(node("btc", "bitcoin", "mainnet", 8332))
        registry.upsert(node("eth", "ethereum", "mainnet", 8545))
        val fake = Fake()
        reconciler(registry, fake).reconcile()
        assertEquals(listOf(Triple(8432, 8332, "btc")), fake.started)
    }

    @Test
    fun reconciling_again_changes_nothing_and_removal_stops_the_proxy()
    {
        val registry = MemoryRegistry()
        registry.upsert(node("ltc1", "ltc", "mainnet", 9332))
        val fake = Fake()
        val r = reconciler(registry, fake)
        r.reconcile(); r.reconcile(); r.reconcile()
        assertEquals(1, fake.started.size)
        assertEquals(1, r.activeProxies())

        registry.remove("ltc1")
        r.reconcile()
        assertEquals(listOf(9432), fake.stopped)
        assertEquals(0, r.activeProxies())
    }

    @Test
    fun a_node_that_moves_to_another_port_gets_its_proxy_restarted()
    {
        val registry = MemoryRegistry()
        registry.upsert(node("d", "doge", "mainnet", 22555))
        val fake = Fake()
        val r = reconciler(registry, fake)
        r.reconcile()
        registry.upsert(node("d", "doge", "mainnet", 22999))
        r.reconcile()
        assertEquals(2, fake.started.size)
        assertEquals(22999, fake.started.last().second)
        assertEquals(1, fake.stopped.size)
    }

    @Test
    fun a_busy_public_port_does_not_break_the_other_nodes()
    {
        val registry = MemoryRegistry()
        registry.upsert(node("a", "bitcoin", "mainnet", 8332))
        registry.upsert(node("b", "ltc", "mainnet", 9332))
        val fake = Fake().also { it.failOn = 8432 }
        val r = reconciler(registry, fake)
        r.reconcile()
        assertEquals(listOf("b"), fake.started.map { it.third })
    }

    @Test
    fun a_bitcoin_cookie_is_found_in_the_chain_folder_and_changes_are_followed()
    {
        val dir = Files.createTempDirectory("btc-datadir")
        Files.createDirectories(dir.resolve("testnet4"))
        Files.writeString(dir.resolve("testnet4").resolve(".cookie"), "__cookie__:aaaa\n")
        assertEquals("__cookie__:aaaa", NodeRpcCredentials.forNode("bitcoin", "testnet4", dir))
        // the node restarted and wrote a new cookie
        Files.writeString(dir.resolve("testnet4").resolve(".cookie"), "__cookie__:bbbb")
        assertEquals("__cookie__:bbbb", NodeRpcCredentials.forNode("bitcoin", "testnet4", dir))
    }

    @Test
    fun a_mainnet_cookie_lives_directly_in_the_datadir()
    {
        val dir = Files.createTempDirectory("btc-main")
        Files.writeString(dir.resolve(".cookie"), "__cookie__:main")
        assertEquals("__cookie__:main", NodeRpcCredentials.forNode("bitcoin", "mainnet", dir))
    }

    @Test
    fun litecoin_and_friends_use_the_toolkit_rpc_auth_file()
    {
        val dir = Files.createTempDirectory("ltc")
        Files.createDirectories(dir.resolve(".toolkit"))
        Files.writeString(dir.resolve(".toolkit").resolve("rpc-auth.env"), "BITCOIN_RPC_USER=rpcnode\nBITCOIN_RPC_PASSWORD=s3cret\n")
        val creds = NodeRpcCredentials.forNode("ltc", "mainnet", dir)
        assertTrue(creds != null && creds.startsWith("rpcnode:"), creds)
    }

    @Test
    fun no_credentials_yet_means_null_not_a_crash()
    {
        assertNull(NodeRpcCredentials.forNode("bitcoin", "mainnet", Files.createTempDirectory("empty")))
        assertNull(NodeRpcCredentials.forNode("doge", "mainnet", Files.createTempDirectory("empty2")))
    }
}
