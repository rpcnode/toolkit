package rpcnode.toolkit.nodes.application.snapshot

import java.time.Instant
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface NodeSnapshotProgressResult
{
    data class Ok(
        val pct: Double?,
        val phase: String,
        val detail: String,
        val ready: Boolean,
        val failed: Boolean,
        val error: String,
        val status: String,
        val logTail: List<String> = emptyList(),
    ) : NodeSnapshotProgressResult

    data object NotFound : NodeSnapshotProgressResult
    data object ServerNotFound : NodeSnapshotProgressResult
    data object AgentUnreachable : NodeSnapshotProgressResult
}

class GetNodeSnapshotProgressUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val pollHost: PollSnapshotOnHost,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(idRaw: String): NodeSnapshotProgressResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return NodeSnapshotProgressResult.NotFound
        val node = nodes.findById(id) ?: return NodeSnapshotProgressResult.NotFound
        val server = servers.find(node.serverId) ?: return NodeSnapshotProgressResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return NodeSnapshotProgressResult.AgentUnreachable
        }
        val host = pollHost.progress(agentUrl, agentKey, node.id.value)
            ?: return NodeSnapshotProgressResult.AgentUnreachable
        var status = node.status.value
        if (host.ready && status == "snapshot_running")
        {
            nodes.updateStatus(id, NodeStatus.parse("snapshot_complete"), clock())
            status = "snapshot_complete"
        }
        else if (host.failed && status == "snapshot_running")
        {
            nodes.updateStatus(id, NodeStatus.parse("snapshot_error"), clock())
            status = "snapshot_error"
        }
        return NodeSnapshotProgressResult.Ok(
            pct = host.pct,
            phase = host.phase,
            detail = host.detail,
            ready = host.ready,
            failed = host.failed,
            error = host.error,
            status = status,
            logTail = host.logTail,
        )
    }
}
