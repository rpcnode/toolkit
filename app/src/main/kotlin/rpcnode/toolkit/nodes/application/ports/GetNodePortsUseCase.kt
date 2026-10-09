package rpcnode.toolkit.nodes.application.ports

import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

/**
 * Fixed ports for one node's network/env from the client catalog — no live bind check on the host.
 * The admin loads this on page open; live status comes from [CheckHostPortsUseCase].
 */
class GetNodePortsUseCase(
    nodes: NodeRepository,
    servers: ServerRepository,
    catalog: ClientProgramCatalog,
)
{
    private val resolver = NodePortsCatalogResolver(nodes, servers, catalog)

    suspend operator fun invoke(idRaw: String): NodePortsResult =
        when (val resolved = resolver.resolve(idRaw))
        {
            is NodePortsCatalogResolver.Resolved.Ready ->
                NodePortsResult.Ok(
                    ports = resolved.catalogPorts.toCatalogNodePorts(),
                    endpoint = resolved.endpoint,
                )
            NodePortsCatalogResolver.Resolved.NotFound -> NodePortsResult.NotFound
            NodePortsCatalogResolver.Resolved.ServerNotFound -> NodePortsResult.ServerNotFound
            NodePortsCatalogResolver.Resolved.NoPorts -> NodePortsResult.NoPorts
        }
}
