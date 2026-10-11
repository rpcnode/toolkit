package rpcnode.toolkit.agent.application.node

import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.domain.model.RunningNode
import rpcnode.toolkit.agent.infrastructure.proxy.CatalogPublicRpcPort
import rpcnode.toolkit.agent.infrastructure.proxy.NodeRpcCredentials
import rpcnode.toolkit.agent.infrastructure.proxy.RPC_PROXY_NETWORKS
import rpcnode.toolkit.agent.infrastructure.proxy.RpcAuthProxy

/** One running login-free proxy; the part the reconciler needs to start and stop it. */
interface RunningRpcProxy
{
    fun stop()
}

/**
 * Keeps one [RpcAuthProxy] alive per bitcoind-style node that is registered with this agent: started when a node
 * appears, stopped when it is removed, restarted when its ports change. Idempotent — call [reconcile] as often as
 * you like (the agent does it every few seconds, so a node started or removed from the panel is picked up quickly).
 */
class RpcProxyReconciler(
    private val registry: RunningNodeRegistry,
    private val listenHost: String = "0.0.0.0",
    private val publicPort: (network: String, env: String) -> Int? = CatalogPublicRpcPort::find,
    private val credentials: (RunningNode) -> String? = NodeRpcCredentials::forNode,
    private val upstreamConcurrency: Int = 64,
    /** null = all methods pass (the port sits behind a firewall); a set = only those. */
    private val allowedMethods: Set<String>? = null,
    private val create: (listenPort: Int, targetPort: Int, node: RunningNode) -> StartedProxy = { listen, target, node ->
        val proxy = RpcAuthProxy(
            listenHost = listenHost,
            listenPort = listen,
            targetPort = target,
            credentials = { credentials(node) },
            upstreamConcurrency = upstreamConcurrency,
            allowedMethods = allowedMethods,
        )
        proxy.start()
        StartedProxy(object : RunningRpcProxy { override fun stop() = proxy.stop() })
    },
)
{
    /** Handle returned by [create]; keeps the factory free of the concrete proxy type (tests use a fake). */
    class StartedProxy(val handle: RunningRpcProxy)

    private data class Key(val listenPort: Int, val targetPort: Int, val nodeDir: String)

    private class Live(val key: Key, val proxy: StartedProxy)

    private val log = LoggerFactory.getLogger(RpcProxyReconciler::class.java)
    private val live = ConcurrentHashMap<String, Live>()
    private val reportedFailure = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun reconcile()
    {
        val wanted = registry.list()
            .filter { it.network.lowercase() in RPC_PROXY_NETWORKS && it.httpPort > 0 }
            .mapNotNull { node ->
                val port = publicPort(node.network, node.env) ?: return@mapNotNull null
                node.nodeId to (node to Key(port, node.httpPort, node.nodeDir))
            }
            .toMap()

        for ((id, running) in live.entries.toList())
        {
            val want = wanted[id]
            if (want == null || want.second != running.key)
            {
                runCatching { running.proxy.handle.stop() }
                live.remove(id)
            }
        }
        for ((id, pair) in wanted)
        {
            if (live.containsKey(id))
            {
                continue
            }
            val (node, key) = pair
            try
            {
                live[id] = Live(key, create(key.listenPort, key.targetPort, node))
                reportedFailure.remove(id)
            }
            catch (e: Exception)
            {
                // typically "Address already in use": say it once, not every few seconds
                if (reportedFailure.add(id))
                {
                    log.warn("rpc proxy for {} on :{} not started: {}", id, key.listenPort, e.message)
                }
            }
        }
    }

    fun activeProxies(): Int = live.size

    @Synchronized
    fun stopAll()
    {
        live.values.forEach { runCatching { it.proxy.handle.stop() } }
        live.clear()
    }
}
