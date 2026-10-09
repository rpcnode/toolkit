package rpcnode.toolkit.nodes.application.status

import java.time.Instant
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository

sealed interface UpdateNodeStatusResult
{
    data class Ok(val status: NodeStatus) : UpdateNodeStatusResult
    data object NotFound : UpdateNodeStatusResult
    data object InvalidStatus : UpdateNodeStatusResult
}

class UpdateNodeStatusUseCase(
    private val nodes: NodeRepository,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(idRaw: String, statusRaw: String): UpdateNodeStatusResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return UpdateNodeStatusResult.NotFound
        val statusText = statusRaw.trim()
        if (statusText.isBlank())
        {
            return UpdateNodeStatusResult.InvalidStatus
        }
        val status = NodeStatus.parse(statusText)
        val updated = nodes.updateStatus(id, status, clock())
        return if (updated)
        {
            UpdateNodeStatusResult.Ok(status)
        }
        else
        {
            UpdateNodeStatusResult.NotFound
        }
    }
}
