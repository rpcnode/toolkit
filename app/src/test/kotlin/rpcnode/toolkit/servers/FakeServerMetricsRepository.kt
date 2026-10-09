package rpcnode.toolkit.servers

import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.model.ServerMetrics
import rpcnode.toolkit.servers.domain.repository.ServerMetricsRepository

class FakeServerMetricsRepository(
    seed: List<ServerMetrics> = emptyList(),
) : ServerMetricsRepository
{
    private val byId = seed.associateByTo(mutableMapOf()) { it.serverId }

    override suspend fun find(serverId: ServerId): ServerMetrics? = byId[serverId]

    override suspend fun upsert(metrics: ServerMetrics)
    {
        byId[metrics.serverId] = metrics
    }
}
