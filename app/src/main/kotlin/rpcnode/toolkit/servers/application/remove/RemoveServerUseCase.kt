package rpcnode.toolkit.servers.application.remove

import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.model.SERVER_REMOVE_STATUS_REMOVING
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface RemoveServerResult
{
    data class Queued(val server: Server) : RemoveServerResult
    data object AlreadyQueued : RemoveServerResult
    data object NotFound : RemoveServerResult
    data object AlreadyDeleted : RemoveServerResult
    data class HasNodes(val count: Int) : RemoveServerResult
}

class RemoveServerUseCase(
    private val servers: ServerRepository,
    private val nodes: NodeRepository,
    private val finish: FinishRemoveServerUseCase,
    private val backgroundScope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
)
{
    suspend operator fun invoke(idRaw: String): RemoveServerResult
    {
        val id = ServerId.parse(idRaw) ?: return RemoveServerResult.NotFound
        val server = servers.find(id) ?: return RemoveServerResult.NotFound
        if (server.isDeleted())
        {
            return RemoveServerResult.AlreadyDeleted
        }
        if (server.isRemoving())
        {
            backgroundScope.launch { finish(id) }
            return RemoveServerResult.AlreadyQueued
        }
        val nodeCount = nodes.listOnServer(id).size
        if (nodeCount > 0)
        {
            return RemoveServerResult.HasNodes(nodeCount)
        }
        val now = Instant.now(clock).toString()
        servers.markRemoving(id, now)
        backgroundScope.launch { finish(id) }
        return RemoveServerResult.Queued(
            server.copy(removeStatus = SERVER_REMOVE_STATUS_REMOVING, updatedAt = now),
        )
    }
}
