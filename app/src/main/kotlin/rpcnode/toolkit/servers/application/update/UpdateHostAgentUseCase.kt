package rpcnode.toolkit.servers.application.update

import java.time.Clock
import java.time.Instant
import rpcnode.toolkit.servers.application.probe.UpdateHostAgent
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface UpdateHostAgentResult
{
    data class Ok(
        val updated: Boolean,
        val version: String,
        val remoteVersion: String,
        val message: String,
    ) : UpdateHostAgentResult
    data object MissingServer : UpdateHostAgentResult
    data object NotFound : UpdateHostAgentResult
    data class Unreachable(val agentUrl: String) : UpdateHostAgentResult
    data class Failed(val error: String, val message: String, val status: Int) : UpdateHostAgentResult
}

class UpdateHostAgentUseCase(
    private val servers: ServerRepository,
    private val update: UpdateHostAgent,
    private val clock: Clock = Clock.systemUTC(),
)
{
    suspend operator fun invoke(serverIdRaw: String, force: Boolean = false): UpdateHostAgentResult
    {
        val id = ServerId.parse(serverIdRaw) ?: return UpdateHostAgentResult.MissingServer
        val server = servers.find(id) ?: return UpdateHostAgentResult.NotFound
        if (!server.isActive())
        {
            return UpdateHostAgentResult.NotFound
        }
        val got = update.update(server.agentUrl, server.agentKey, force)
            ?: return UpdateHostAgentResult.Unreachable(server.agentUrl)
        if (!got.ok)
        {
            return UpdateHostAgentResult.Failed(
                error = got.error.ifBlank { "update_failed" },
                message = got.message.ifBlank { "Agent at ${server.agentUrl} did not update" },
                status = got.status,
            )
        }
        val version = got.version.trim().ifEmpty { got.remoteVersion.trim() }
        if (version.isNotEmpty())
        {
            servers.setAgentVersion(server.id, version, Instant.now(clock).toString())
        }
        return UpdateHostAgentResult.Ok(
            updated = got.updated,
            version = version.ifEmpty { server.agentVersion },
            remoteVersion = got.remoteVersion.trim(),
            message = got.message,
        )
    }
}
