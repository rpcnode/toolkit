package rpcnode.toolkit.servers.application.list

import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.model.SERVER_REMOVE_STATUS_REMOVING
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerMetrics
import rpcnode.toolkit.servers.domain.model.metricsStatus
import rpcnode.toolkit.servers.domain.repository.ServerMetricsRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

data class ListedServer(
    val server: Server,
    val metrics: ServerMetrics?,
    val metricsStatus: String,
    val metricsStale: Boolean,
    val nodesCount: Int,
    val canDelete: Boolean,
    val removeStatus: String,
)

class ListServersUseCase(
    private val servers: ServerRepository,
    private val metrics: ServerMetricsRepository,
    private val nodes: NodeRepository,
)
{
    suspend operator fun invoke(): List<ListedServer>
    {
        val countByServer = nodes.list().groupingBy { it.serverId }.eachCount()
        return servers.list().map { server ->
            val m = metrics.find(server.id)
            val status = metricsStatus(m)
            val n = countByServer[server.id] ?: 0
            val removing = server.isRemoving()
            ListedServer(
                server = server,
                metrics = m,
                metricsStatus = if (removing) SERVER_REMOVE_STATUS_REMOVING else status,
                metricsStale = status != "online",
                nodesCount = n,
                canDelete = n == 0 && !removing,
                removeStatus = if (removing) SERVER_REMOVE_STATUS_REMOVING else "",
            )
        }
    }
}
