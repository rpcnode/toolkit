package rpcnode.toolkit.nodes.application.ports

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.servers.application.probe.CheckAgentPorts
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

/**
 * Live port check on a host: catalog ports for network/env merged with the agent bind probe.
 * Host scope — same pattern as [rpcnode.toolkit.nodes.application.disks.GetHostDisksUseCase].
 */
class CheckHostPortsUseCase(
    private val servers: ServerRepository,
    private val catalog: ClientProgramCatalog,
    private val checkAgentPorts: CheckAgentPorts,
)
{
    suspend operator fun invoke(serverIdRaw: String, network: NetworkId, env: EnvId): NodePortsResult
    {
        val sid = ServerId.parse(serverIdRaw.trim()) ?: return NodePortsResult.ServerNotFound
        val server = servers.find(sid) ?: return NodePortsResult.ServerNotFound

        val catalogPorts = catalog.programsFor(network, env).flatMap { it.ports }
        if (catalogPorts.isEmpty())
        {
            return NodePortsResult.NoPorts
        }

        val endpoint = endpointFor(server.agentUrl, catalogPorts)
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return NodePortsResult.AgentUnreachable(catalogPorts.toCatalogNodePorts(), endpoint)
        }

        val checked = checkAgentPorts.checkPorts(agentUrl, agentKey, catalogPorts.map { it.port })
            ?: return NodePortsResult.AgentUnreachable(catalogPorts.toCatalogNodePorts(), endpoint)

        val byPort = checked.associateBy { it.port }
        val merged = catalogPorts.map { p ->
            val c = byPort[p.port]
            p.toNodePort(free = c?.free, holder = c?.holder)
        }
        return NodePortsResult.Ok(ports = merged, endpoint = endpoint)
    }
}
