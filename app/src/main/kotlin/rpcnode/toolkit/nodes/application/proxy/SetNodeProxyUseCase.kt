package rpcnode.toolkit.nodes.application.proxy

import java.time.Instant
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.proxies.domain.model.ProxyId
import rpcnode.toolkit.proxies.domain.repository.ProxyRepository

sealed interface SetNodeProxyResult
{
    data class Saved(val nodeId: String, val proxyId: String) : SetNodeProxyResult
    data object NodeNotFound : SetNodeProxyResult
    data object ProxyNotFound : SetNodeProxyResult
}

/**
 * Pick the node's proxy from the global list (blank = direct). Only the DB changes here: the host gets it on the
 * next Start / Restart and on the next Snapshot start.
 */
class SetNodeProxyUseCase(
    private val nodes: NodeRepository,
    private val proxies: ProxyRepository,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(nodeIdRaw: String, proxyIdRaw: String?): SetNodeProxyResult
    {
        val id = NodeId.parse(nodeIdRaw) ?: return SetNodeProxyResult.NodeNotFound
        nodes.findById(id) ?: return SetNodeProxyResult.NodeNotFound
        val proxyId = proxyIdRaw?.trim().orEmpty()
        if (proxyId.isNotEmpty())
        {
            val pid = ProxyId.parse(proxyId) ?: return SetNodeProxyResult.ProxyNotFound
            proxies.find(pid) ?: return SetNodeProxyResult.ProxyNotFound
        }
        nodes.saveProxy(id, proxyId, clock())
        return SetNodeProxyResult.Saved(id.value, proxyId)
    }
}
