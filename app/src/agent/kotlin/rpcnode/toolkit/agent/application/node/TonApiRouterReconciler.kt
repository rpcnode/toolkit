package rpcnode.toolkit.agent.application.node

import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.domain.model.RunningNode
import rpcnode.toolkit.agent.infrastructure.proxy.CatalogPublicRpcPort
import rpcnode.toolkit.agent.infrastructure.proxy.TonApiRouter

/**
 * Keeps one [TonApiRouter] alive per TON node registered with this agent: v2 (the node's HTTP API port) and v3 (the
 * indexer port from the catalog) behind the catalog `rpc_public` port. Same life cycle as [RpcProxyReconciler]:
 * idempotent, called every few seconds.
 */
class TonApiRouterReconciler(
    private val registry: RunningNodeRegistry,
    private val listenHost: String = "0.0.0.0",
    private val catalogPort: (network: String, env: String, role: String) -> Int? = CatalogPublicRpcPort::find,
    private val create: (listenPort: Int, v2Port: Int, v3Port: Int) -> RpcProxyReconciler.StartedProxy = { listen, v2, v3 ->
        val router = TonApiRouter(listenHost = listenHost, listenPort = listen, v2Port = v2, v3Port = v3)
        router.start()
        RpcProxyReconciler.StartedProxy(object : RunningRpcProxy { override fun stop() = router.stop() })
    },
)
{
    private data class Key(val listenPort: Int, val v2Port: Int, val v3Port: Int)

    private class Live(val key: Key, val proxy: RpcProxyReconciler.StartedProxy)

    private val log = LoggerFactory.getLogger(TonApiRouterReconciler::class.java)
    private val live = ConcurrentHashMap<String, Live>()
    private val reportedFailure = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun reconcile()
    {
        val wanted = registry.list()
            .filter { it.network.equals("ton", ignoreCase = true) && it.httpPort > 0 }
            .mapNotNull { node ->
                val listen = catalogPort(node.network, node.env, CatalogPublicRpcPort.ROLE) ?: return@mapNotNull null
                val v3 = catalogPort(node.network, node.env, "indexer") ?: 0
                node.nodeId to Key(listen, node.httpPort, v3)
            }
            .toMap()

        for ((id, running) in live.entries.toList())
        {
            if (wanted[id] != running.key)
            {
                runCatching { running.proxy.handle.stop() }
                live.remove(id)
            }
        }
        for ((id, key) in wanted)
        {
            if (live.containsKey(id))
            {
                continue
            }
            try
            {
                live[id] = Live(key, create(key.listenPort, key.v2Port, key.v3Port))
                reportedFailure.remove(id)
            }
            catch (e: Exception)
            {
                if (reportedFailure.add(id))
                {
                    log.warn("ton router for {} on :{} not started: {}", id, key.listenPort, e.message)
                }
            }
        }
    }

    fun activeRouters(): Int = live.size

    @Synchronized
    fun stopAll()
    {
        live.values.forEach { runCatching { it.proxy.handle.stop() } }
        live.clear()
    }
}
