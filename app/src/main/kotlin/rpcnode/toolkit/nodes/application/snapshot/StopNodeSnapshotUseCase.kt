package rpcnode.toolkit.nodes.application.snapshot

import java.time.Instant
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface StopNodeSnapshotResult
{
    data object Ok : StopNodeSnapshotResult
    data object NotFound : StopNodeSnapshotResult
    data object ServerNotFound : StopNodeSnapshotResult
    data object AgentUnreachable : StopNodeSnapshotResult
}

class StopNodeSnapshotUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val stopOnHost: StopSnapshotOnHost,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(idRaw: String): StopNodeSnapshotResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return StopNodeSnapshotResult.NotFound
        val node = nodes.findById(id) ?: return StopNodeSnapshotResult.NotFound
        val server = servers.find(node.serverId) ?: return StopNodeSnapshotResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return StopNodeSnapshotResult.AgentUnreachable
        }
        stopOnHost.stop(agentUrl, agentKey, node.id.value, wipeDest = true)
            ?: return StopNodeSnapshotResult.AgentUnreachable
        nodes.updateStatus(id, NodeStatus.parse("needs_snapshot"), clock())
        return StopNodeSnapshotResult.Ok
    }
}
