package rpcnode.toolkit.nodes.application.version

import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.application.snapshot.ResolveSnapshotDestDirUseCase
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

data class NodeClientVersionView(
    val nodeId: String,
    val clientVersion: String,
    val path: String,
)

sealed interface GetNodeClientVersionResult
{
    data class Ok(val view: NodeClientVersionView) : GetNodeClientVersionResult
    data object NotFound : GetNodeClientVersionResult
    data object ServerNotFound : GetNodeClientVersionResult
    data object AgentUnreachable : GetNodeClientVersionResult
    data object InvalidAgentKey : GetNodeClientVersionResult
}

/** Panel → host agent: chain client version from `{nodeDir}/VERSION`. */
class GetNodeClientVersionUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val facts: NetworkFactsRepository,
    private val clients: ClientVersionRepository,
    private val resolveDestDir: ResolveSnapshotDestDirUseCase,
    private val fetchOnHost: FetchNodeClientVersionOnHost,
)
{
    suspend operator fun invoke(idRaw: String): GetNodeClientVersionResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return GetNodeClientVersionResult.NotFound
        val node = nodes.findById(id) ?: return GetNodeClientVersionResult.NotFound
        val server = servers.find(node.serverId) ?: return GetNodeClientVersionResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return GetNodeClientVersionResult.AgentUnreachable
        }
        val nodeDir = resolveDestDir(node)?.trim()?.takeIf { it.isNotEmpty() }
        val seed = resolveSeed(node)
        return when (
            val host = fetchOnHost.clientVersion(
                agentUrl = agentUrl,
                token = agentKey,
                nodeId = node.id.value,
                nodeDir = nodeDir,
                seed = seed,
            )
        )
        {
            is FetchNodeClientVersionResult.Ok ->
                GetNodeClientVersionResult.Ok(
                    NodeClientVersionView(
                        nodeId = node.id.value,
                        clientVersion = host.version.clientVersion,
                        path = host.version.path,
                    ),
                )
            FetchNodeClientVersionResult.Empty -> GetNodeClientVersionResult.Ok(
                NodeClientVersionView(
                    nodeId = node.id.value,
                    clientVersion = "",
                    path = "",
                ),
            )
            FetchNodeClientVersionResult.Unauthorized -> GetNodeClientVersionResult.InvalidAgentKey
            FetchNodeClientVersionResult.Unreachable -> GetNodeClientVersionResult.AgentUnreachable
        }
    }

    private suspend fun resolveSeed(node: rpcnode.toolkit.nodes.domain.model.Node): String?
    {
        val fromDb = node.clientVersion.trim()
        if (fromDb.isNotEmpty())
        {
            return fromDb
        }
        val program = facts.factsFor(node.network)?.clientConfig?.program?.trim().orEmpty()
        if (program.isEmpty())
        {
            return null
        }
        return clients.find(node.network, node.env, program)?.currentVersion?.trim()?.takeIf { it.isNotEmpty() }
    }
}
