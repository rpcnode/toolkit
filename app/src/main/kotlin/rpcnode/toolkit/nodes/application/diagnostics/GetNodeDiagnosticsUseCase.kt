package rpcnode.toolkit.nodes.application.diagnostics

import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

/** A check sent to the host agent: the catalog port role already turned into this env's port number. */
data class NodeDiagnosticCheck(
    val id: String,
    val port: Int = 0,
    val protocol: String = "",
    val params: Map<String, String> = emptyMap(),
)

/** level: ok | info | warn | error | unknown. */
data class NodeDiagnosticItem(
    val id: String,
    val level: String,
    val title: String,
    val detail: String = "",
    val hint: String = "",
)

sealed interface RunNodeDiagnosticsOnHostResult
{
    data class Ok(val items: List<NodeDiagnosticItem>) : RunNodeDiagnosticsOnHostResult
    data object Unauthorized : RunNodeDiagnosticsOnHostResult
    /** Agent without the endpoint (older than the panel). */
    data object Unsupported : RunNodeDiagnosticsOnHostResult
    data object Unreachable : RunNodeDiagnosticsOnHostResult
}

fun interface RunNodeDiagnosticsOnHost
{
    suspend fun run(agentUrl: String, token: String, nodeId: String, checks: List<NodeDiagnosticCheck>): RunNodeDiagnosticsOnHostResult
}

sealed interface GetNodeDiagnosticsResult
{
    data class Ok(val items: List<NodeDiagnosticItem>) : GetNodeDiagnosticsResult
    data object NotFound : GetNodeDiagnosticsResult
    data object ServerNotFound : GetNodeDiagnosticsResult
    data object AgentUnreachable : GetNodeDiagnosticsResult
    data object InvalidAgentKey : GetNodeDiagnosticsResult
    data object AgentTooOld : GetNodeDiagnosticsResult
}

/**
 * Node page warnings: the checks listed in `chains/<id>/network.yml` → `diagnostics`, run on the host agent.
 * A network without the list answers an empty Ok (nothing to show).
 */
class GetNodeDiagnosticsUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val facts: NetworkFactsRepository,
    private val catalog: ClientProgramCatalog,
    private val runOnHost: RunNodeDiagnosticsOnHost,
)
{
    suspend operator fun invoke(idRaw: String): GetNodeDiagnosticsResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return GetNodeDiagnosticsResult.NotFound
        val node = nodes.findById(id) ?: return GetNodeDiagnosticsResult.NotFound
        val declared = facts.factsFor(node.network)?.diagnostics.orEmpty()
        if (declared.isEmpty())
        {
            return GetNodeDiagnosticsResult.Ok(emptyList())
        }
        val server = servers.find(node.serverId) ?: return GetNodeDiagnosticsResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return GetNodeDiagnosticsResult.AgentUnreachable
        }
        val ports = catalog.programsFor(node.network, node.env).flatMap { it.ports }
        val checks = declared.map { d ->
            NodeDiagnosticCheck(
                id = d.id,
                port = d.port?.let { role -> ports.firstOrNull { it.role.equals(role, ignoreCase = true) }?.port } ?: 0,
                protocol = d.protocol.orEmpty(),
                params = d.params,
            )
        }
        return when (val host = runOnHost.run(agentUrl, agentKey, node.id.value, checks))
        {
            is RunNodeDiagnosticsOnHostResult.Ok -> GetNodeDiagnosticsResult.Ok(host.items)
            RunNodeDiagnosticsOnHostResult.Unauthorized -> GetNodeDiagnosticsResult.InvalidAgentKey
            RunNodeDiagnosticsOnHostResult.Unsupported -> GetNodeDiagnosticsResult.AgentTooOld
            RunNodeDiagnosticsOnHostResult.Unreachable -> GetNodeDiagnosticsResult.AgentUnreachable
        }
    }
}
