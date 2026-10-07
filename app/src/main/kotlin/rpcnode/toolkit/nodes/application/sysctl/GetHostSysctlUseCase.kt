package rpcnode.toolkit.nodes.application.sysctl

import rpcnode.toolkit.nodes.domain.model.HostSysctlCatalog
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface HostSysctlResult
{
    data class Ok(val catalog: HostSysctlCatalog) : HostSysctlResult

    data object ServerNotFound : HostSysctlResult
    data object AgentUnreachable : HostSysctlResult
}

/** Host `/proc/sys` + Anza recommended values — server scope (no network/env). */
class GetHostSysctlUseCase(
    private val servers: ServerRepository,
    private val reader: HostSysctlReader,
)
{
    suspend operator fun invoke(serverId: String): HostSysctlResult
    {
        val sid = ServerId.parse(serverId.trim()) ?: return HostSysctlResult.ServerNotFound
        val server = servers.find(sid) ?: return HostSysctlResult.ServerNotFound
        val token = server.agentKey.trim()
        if (server.agentUrl.isBlank() || token.isBlank())
        {
            return HostSysctlResult.AgentUnreachable
        }
        val catalog = reader.read(server.agentUrl, token) ?: return HostSysctlResult.AgentUnreachable
        return HostSysctlResult.Ok(catalog)
    }
}
