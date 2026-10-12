package rpcnode.toolkit.agent.application.node

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import rpcnode.toolkit.agent.domain.model.RunningNode
import rpcnode.toolkit.agent.infrastructure.proxy.CatalogPublicRpcPort

class TonApiRouterReconcilerTest
{
    private class MemoryRegistry : RunningNodeRegistry
    {
        val nodes = linkedMapOf<String, RunningNode>()
        override fun upsert(node: RunningNode) { nodes[node.nodeId] = node }
        override fun remove(nodeId: String) { nodes.remove(nodeId) }
        override fun get(nodeId: String): RunningNode? = nodes[nodeId]
        override fun list(): List<RunningNode> = nodes.values.toList()
    }

    private val started = mutableListOf<Triple<Int, Int, Int>>()
    private val stopped = mutableListOf<Int>()

    private fun reconciler(registry: RunningNodeRegistry) = TonApiRouterReconciler(
        registry = registry,
        create = { listen, v2, v3 ->
            started += Triple(listen, v2, v3)
            RpcProxyReconciler.StartedProxy(object : RunningRpcProxy { override fun stop() { stopped += listen } })
        },
    )

    private fun node(id: String, network: String, env: String, http: Int) =
        RunningNode(nodeId = id, network = network, env = env, nodeDir = "/data/$id", httpPort = http, pid = 1)

    @Test
    fun the_catalog_gives_ton_a_public_router_port_and_an_indexer_port()
    {
        assertEquals(8087, CatalogPublicRpcPort.find("ton", "mainnet"))
        assertEquals(8187, CatalogPublicRpcPort.find("ton", "mainnet", "indexer"))
        assertEquals(8088, CatalogPublicRpcPort.find("ton", "testnet"))
        assertEquals(8188, CatalogPublicRpcPort.find("ton", "testnet", "indexer"))
        assertNull(CatalogPublicRpcPort.find("ton", "mainnet", "nope"))
        assertEquals(8432, CatalogPublicRpcPort.find("bitcoin", "mainnet"), "the bitcoind lookup is unchanged")
    }

    @Test
    fun a_ton_node_gets_one_router_and_other_networks_are_ignored()
    {
        val registry = MemoryRegistry()
        registry.upsert(node("a", "ton", "mainnet", 8081))
        registry.upsert(node("b", "ethereum", "mainnet", 8545))
        val r = reconciler(registry)

        r.reconcile()
        r.reconcile()

        assertEquals(listOf(Triple(8087, 8081, 8187)), started)
        assertEquals(1, r.activeRouters())
    }

    @Test
    fun a_removed_node_stops_its_router_and_a_changed_port_restarts_it()
    {
        val registry = MemoryRegistry()
        registry.upsert(node("a", "ton", "testnet", 8082))
        val r = reconciler(registry)
        r.reconcile()

        registry.upsert(node("a", "ton", "testnet", 9999))
        r.reconcile()
        assertEquals(listOf(Triple(8088, 8082, 8188), Triple(8088, 9999, 8188)), started)
        assertEquals(listOf(8088), stopped)

        registry.remove("a")
        r.reconcile()
        assertEquals(0, r.activeRouters())
        assertEquals(listOf(8088, 8088), stopped)
    }
}
