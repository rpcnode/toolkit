package rpcnode.toolkit.nodes.application.get

import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.application.list.withClientPinStatus
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository

class GetNodeUseCase(
    private val nodes: NodeRepository,
    private val clients: ClientVersionRepository,
    private val facts: NetworkFactsRepository,
)
{
    suspend operator fun invoke(idRaw: String): Node?
    {
        val id = NodeId.parse(idRaw.trim()) ?: return null
        val node = nodes.findById(id) ?: return null
        return node.withClientPinStatus(clients, facts)
    }
}
