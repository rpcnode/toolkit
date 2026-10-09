package rpcnode.toolkit.servers.domain.repository

import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.model.ServerMetrics

interface ServerMetricsRepository
{
    suspend fun find(serverId: ServerId): ServerMetrics?

    suspend fun upsert(metrics: ServerMetrics)
}
