package rpcnode.toolkit.nodes.application.disks

import rpcnode.toolkit.nodes.domain.model.HostDiskCatalog
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface HostDisksResult
{
    data class Ok(
        val catalog: HostDiskCatalog,
        val summary: String,
    ) : HostDisksResult

    data object ServerNotFound : HostDisksResult
    data object AgentUnreachable : HostDisksResult
}

/** Host block devices from the server agent — server scope only (no network/env). */
class GetHostDisksUseCase(
    private val servers: ServerRepository,
    private val reader: HostDiskReader,
)
{
    suspend operator fun invoke(serverId: String): HostDisksResult
    {
        val sid = ServerId.parse(serverId.trim()) ?: return HostDisksResult.ServerNotFound
        val server = servers.find(sid) ?: return HostDisksResult.ServerNotFound
        val token = server.agentKey.trim()
        if (server.agentUrl.isBlank() || token.isBlank())
        {
            return HostDisksResult.AgentUnreachable
        }
        val catalog = reader.read(server.agentUrl, token) ?: return HostDisksResult.AgentUnreachable
        return HostDisksResult.Ok(
            catalog = catalog,
            summary = summarizeHostInventory(catalog),
        )
    }
}

internal fun summarizeHostInventory(catalog: HostDiskCatalog): String
{
    val nvme = catalog.disks.count { it.tran.equals("nvme", ignoreCase = true) }
    val mounts = catalog.mounts.count { it.target != "/" && !it.target.startsWith("/boot") }
    return when
    {
        nvme >= 2 && mounts >= 2 -> "JBOD: $nvme NVMe, $mounts data mounts"
        nvme >= 1 -> "$nvme NVMe · ${catalog.mounts.size} mount(s)"
        else -> "${catalog.disks.size} disk(s) · ${catalog.mounts.size} mount(s)"
    }
}
