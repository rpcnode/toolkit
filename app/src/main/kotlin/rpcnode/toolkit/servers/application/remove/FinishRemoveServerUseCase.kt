package rpcnode.toolkit.servers.application.remove

import java.time.Clock
import java.time.Instant
import rpcnode.toolkit.servers.application.probe.UnenrollHostAgent
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

/** Tells the host agent to forget this panel, then soft-deletes the registry row. */
class FinishRemoveServerUseCase(
    private val servers: ServerRepository,
    private val unenroll: UnenrollHostAgent,
    private val clock: Clock = Clock.systemUTC(),
)
{
    suspend operator fun invoke(id: ServerId)
    {
        val server = servers.find(id) ?: return
        if (server.isDeleted())
        {
            return
        }
        try
        {
            unenroll.unenroll(server.agentUrl, server.agentKey)
        }
        catch (_: Exception)
        {
            // Dead host must not stick in the registry.
        }
        servers.markDeleted(id, Instant.now(clock).toString())
    }
}
