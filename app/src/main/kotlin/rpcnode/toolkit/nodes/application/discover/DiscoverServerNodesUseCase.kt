package rpcnode.toolkit.nodes.application.discover

import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

data class DiscoveredNodeItem(
    val network: String,
    val env: String,
    val label: String,
    val hostStatus: String = "",
    val source: String = "",
    val publicPort: Int = 0,
    val agentPort: Int = 0,
    val nodeHttpPort: Int = 0,
    val p2pPort: Int = 0,
)

sealed interface DiscoverServerNodesResult
{
    data class Ok(val items: List<DiscoveredNodeItem>) : DiscoverServerNodesResult
    data object NotFound : DiscoverServerNodesResult
    data object AgentNotConfigured : DiscoverServerNodesResult
    data object InvalidAgentKey : DiscoverServerNodesResult
    data class AgentUnreachable(val detail: String = "") : DiscoverServerNodesResult
}

/**
 * Ask the host agent which network/env pairs are already provisioned, then keep
 * catalog-known pairs that this panel does not yet track on that server.
 */
class DiscoverServerNodesUseCase(
    private val servers: ServerRepository,
    private val nodes: NodeRepository,
    private val catalog: NetworkCatalog,
    private val fetchOnHost: FetchHostInstalledNodes,
)
{
    suspend operator fun invoke(serverIdRaw: String): DiscoverServerNodesResult
    {
        val serverId = ServerId.parse(serverIdRaw.trim()) ?: return DiscoverServerNodesResult.NotFound
        val server = servers.find(serverId) ?: return DiscoverServerNodesResult.NotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return DiscoverServerNodesResult.AgentNotConfigured
        }
        val host = when (val got = fetchOnHost.list(agentUrl, agentKey))
        {
            is FetchHostInstalledNodesResult.Ok -> got.items
            FetchHostInstalledNodesResult.Unauthorized -> return DiscoverServerNodesResult.InvalidAgentKey
            is FetchHostInstalledNodesResult.Unreachable ->
                return DiscoverServerNodesResult.AgentUnreachable(got.detail)
        }
        val existing = nodes.listOnServer(serverId)
            .map { it.network.value.lowercase() to it.env.value.lowercase() }
            .toSet()
        val found = mutableListOf<DiscoveredNodeItem>()
        val seen = mutableSetOf<String>()
        for (item in host)
        {
            val networkRaw = item.network.trim().lowercase()
            val envRaw = item.env.trim()
            if (networkRaw.isEmpty() || envRaw.isEmpty())
            {
                continue
            }
            val networkId = NetworkId.parse(networkRaw) ?: continue
            val chain = catalog.find(networkId) ?: continue
            val env = chain.env(envRaw) ?: continue
            val key = "${chain.id.value}/${env.id.value}"
            if (key in seen)
            {
                continue
            }
            if ((chain.id.value.lowercase() to env.id.value.lowercase()) in existing)
            {
                continue
            }
            seen += key
            found += DiscoveredNodeItem(
                network = chain.id.value,
                env = env.id.value,
                label = chain.displayLabel(),
                hostStatus = item.status,
                source = item.source,
                publicPort = item.publicPort,
                agentPort = item.agentPort,
                nodeHttpPort = item.nodeHttpPort,
                p2pPort = item.p2pPort,
            )
        }
        return DiscoverServerNodesResult.Ok(found)
    }
}
