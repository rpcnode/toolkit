package rpcnode.toolkit.nodes.application.rpcauth

import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreRpcAuth
import rpcnode.toolkit.nodes.application.snapshot.ResolveSnapshotDestDirUseCase
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

data class NodeRpcAuthView(
    val nodeId: String,
    val user: String,
    val password: String,
    val path: String,
)

sealed interface GetNodeRpcAuthResult
{
    data class Ok(val view: NodeRpcAuthView) : GetNodeRpcAuthResult
    data object NotApplicable : GetNodeRpcAuthResult
    data object NotFound : GetNodeRpcAuthResult
    data object ServerNotFound : GetNodeRpcAuthResult
    data object AgentUnreachable : GetNodeRpcAuthResult
    data object InvalidAgentKey : GetNodeRpcAuthResult
}

/** Panel → host agent: Dash / LTC / Doge / BCH JSON-RPC user + password. */
class GetNodeRpcAuthUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val resolveDestDir: ResolveSnapshotDestDirUseCase,
    private val fetchOnHost: FetchNodeRpcAuthOnHost,
)
{
    suspend operator fun invoke(idRaw: String): GetNodeRpcAuthResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return GetNodeRpcAuthResult.NotFound
        val node = nodes.findById(id) ?: return GetNodeRpcAuthResult.NotFound
        if (!BitcoreRpcAuth.needsRpcUserPassword(node.network.value))
        {
            return GetNodeRpcAuthResult.NotApplicable
        }
        val server = servers.find(node.serverId) ?: return GetNodeRpcAuthResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return GetNodeRpcAuthResult.AgentUnreachable
        }
        val nodeDir = resolveDestDir(node)?.trim()?.takeIf { it.isNotEmpty() }
        return when (
            val host = fetchOnHost.rpcAuth(
                agentUrl = agentUrl,
                token = agentKey,
                nodeId = node.id.value,
                network = node.network.value,
                nodeDir = nodeDir,
            )
        )
        {
            is FetchNodeRpcAuthResult.Ok ->
                GetNodeRpcAuthResult.Ok(
                    NodeRpcAuthView(
                        nodeId = node.id.value,
                        user = host.auth.user,
                        password = host.auth.password,
                        path = host.auth.path,
                    ),
                )
            FetchNodeRpcAuthResult.NotApplicable -> GetNodeRpcAuthResult.NotApplicable
            FetchNodeRpcAuthResult.Empty -> GetNodeRpcAuthResult.NotFound
            FetchNodeRpcAuthResult.Unauthorized -> GetNodeRpcAuthResult.InvalidAgentKey
            FetchNodeRpcAuthResult.Unreachable -> GetNodeRpcAuthResult.AgentUnreachable
        }
    }
}
