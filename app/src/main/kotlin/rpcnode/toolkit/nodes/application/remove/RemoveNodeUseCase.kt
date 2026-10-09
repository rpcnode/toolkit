package rpcnode.toolkit.nodes.application.remove

import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface RemoveNodeResult
{
    data class Removed(val node: Node, val mode: RemoveNodeMode) : RemoveNodeResult
    data object NotFound : RemoveNodeResult
    data object ServerNotFound : RemoveNodeResult
    data object AgentUnreachable : RemoveNodeResult
    data object InvalidAgentKey : RemoveNodeResult
    data class Failed(val error: String, val message: String) : RemoveNodeResult
}

/**
 * PANEL — drop the panel row only.
 * AGENTS — stop+delete host systemd unit, keep chain data, then drop the row.
 * WIPE — agents + wipe node_dir contents, then drop the row.
 */
class RemoveNodeUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository? = null,
    private val resolveDestDir: (suspend (Node) -> String?)? = null,
    private val removeOnHost: RemoveNodeOnHost? = null,
)
{
    suspend operator fun invoke(idRaw: String, mode: RemoveNodeMode): RemoveNodeResult
    {
        val id = NodeId.parse(idRaw) ?: return RemoveNodeResult.NotFound
        val node = nodes.findById(id) ?: return RemoveNodeResult.NotFound
        if (mode == RemoveNodeMode.PANEL)
        {
            nodes.delete(id)
            return RemoveNodeResult.Removed(node, mode)
        }
        val serverRepo = servers ?: return RemoveNodeResult.Failed(
            "not_wired",
            "Host removal is not wired in this build",
        )
        val hostRemove = removeOnHost ?: return RemoveNodeResult.Failed(
            "not_wired",
            "Host removal is not wired in this build",
        )
        val server = serverRepo.find(node.serverId) ?: return RemoveNodeResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return RemoveNodeResult.AgentUnreachable
        }
        val nodeDir = resolveDestDir?.invoke(node)?.trim()?.takeIf { it.isNotEmpty() }
        if (mode == RemoveNodeMode.WIPE && nodeDir == null)
        {
            return RemoveNodeResult.Failed(
                "no_disk_layout",
                "Cannot wipe — node has no disk layout / dest dir yet. Use Agents or Panel, or save Disks first.",
            )
        }
        val host = hostRemove.remove(
            agentUrl,
            agentKey,
            RemoveNodeOnHostCommand(
                nodeId = node.id.value,
                network = node.network.value,
                env = node.env.value,
                nodeDir = nodeDir,
                wipeData = mode == RemoveNodeMode.WIPE,
            ),
        ) ?: return RemoveNodeResult.AgentUnreachable
        when (host)
        {
            RemoveNodeOnHostResult.Ok -> Unit
            is RemoveNodeOnHostResult.Failed ->
            {
                if (host.error == "invalid_agent_key" || host.error == "unauthorized")
                {
                    return RemoveNodeResult.InvalidAgentKey
                }
                return RemoveNodeResult.Failed(host.error, host.message)
            }
        }
        nodes.delete(id)
        return RemoveNodeResult.Removed(node, mode)
    }
}
