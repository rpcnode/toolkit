package rpcnode.toolkit.clients.infrastructure.fleet

import rpcnode.toolkit.clients.application.build.BuildHost
import rpcnode.toolkit.clients.application.build.BuildHostDirectory
import rpcnode.toolkit.clients.domain.model.ClientBuildSpec
import rpcnode.toolkit.servers.domain.model.ServerMetrics
import rpcnode.toolkit.servers.domain.model.metricsStatus
import rpcnode.toolkit.servers.domain.repository.ServerMetricsRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

/**
 * Enrolled, reachable servers with a known CPU architecture. The more cores and RAM and the less busy right now, the
 * earlier a host is picked — a compile takes every core, so an idle big host beats a loaded one running a node.
 */
class ServerBuildHostDirectory(
    private val servers: ServerRepository,
    private val metrics: ServerMetricsRepository,
) : BuildHostDirectory
{
    override suspend fun hosts(): List<BuildHost> =
        servers.list()
            .filter { it.isActive() && it.agentUrl.isNotBlank() && it.agentKey.isNotBlank() }
            .mapNotNull { server ->
                val m = metrics.find(server.id)
                if (metricsStatus(m) == "offline")
                {
                    return@mapNotNull null
                }
                val arch = ClientBuildSpec.normalizeArch(server.arch.ifBlank { m?.arch.orEmpty() })
                    ?: return@mapNotNull null
                BuildHost(
                    id = server.id.value,
                    name = server.name,
                    agentUrl = server.agentUrl,
                    token = server.agentKey,
                    arch = arch,
                    power = power(m),
                )
            }

    private fun power(m: ServerMetrics?): Long
    {
        val cores = (m?.ncpu ?: 1).coerceAtLeast(1)
        val memGib = ((m?.memTotalMb ?: 1024.0) / 1024.0).coerceAtLeast(1.0)
        val idle = (1.0 - (m?.cpuPct ?: 0.0) / 100.0).coerceIn(0.1, 1.0)
        return (cores * memGib * idle * 100).toLong().coerceAtLeast(1)
    }
}
