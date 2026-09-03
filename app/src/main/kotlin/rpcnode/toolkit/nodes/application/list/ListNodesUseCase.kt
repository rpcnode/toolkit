package rpcnode.toolkit.nodes.application.list

import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.application.ingest.compareToLatest
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.repository.NodeRepository

/**
 * Lists nodes with [Node.clientLatest] / [Node.clientUpdateAvailable] taken from the
 * Clients pin (`client_versions`), not the denormalized DB columns.
 * Update is needed when [Node.clientVersion] ≠ pin latest.
 */
class ListNodesUseCase(
    private val nodes: NodeRepository,
    private val clients: ClientVersionRepository,
    private val facts: NetworkFactsRepository,
)
{
    suspend operator fun invoke(): List<Node> =
        nodes.list().map { it.withClientPinStatus(clients, facts) }
}

/** Overlay pin latest onto a node row for API responses. */
suspend fun Node.withClientPinStatus(
    clients: ClientVersionRepository,
    facts: NetworkFactsRepository,
): Node
{
    val compared = compareToLatest(this, clientVersion, clients, facts)
    return copy(
        clientLatest = compared.latest,
        clientUpdateAvailable = compared.updateAvailable,
    )
}
