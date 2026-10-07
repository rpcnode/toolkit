package rpcnode.toolkit.servers.application.update

import java.time.Clock
import java.time.Instant
import rpcnode.toolkit.servers.application.probe.EnrollHostAgent
import rpcnode.toolkit.servers.application.probe.EnrollHostAgentResult
import rpcnode.toolkit.servers.application.probe.HostAgentClient
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface UpdateServerResult
{
    data class Updated(val server: Server) : UpdateServerResult
    data object NotFound : UpdateServerResult
    data object Removing : UpdateServerResult
    data object AgentUrlRequired : UpdateServerResult
    data object AgentKeyRequired : UpdateServerResult
    data object PanelUrlRequired : UpdateServerResult
    data class EnrollFailed(val agentUrl: String, val detail: String = "") : UpdateServerResult
    data class PanelUnreachable(val agentUrl: String, val panelUrl: String) : UpdateServerResult
    data class InvalidAgentKey(val agentUrl: String) : UpdateServerResult
}

/** Edit an existing registry row (name / agent URL / key) and re-check enrollment. */
class UpdateServerUseCase(
    private val servers: ServerRepository,
    private val enroll: EnrollHostAgent,
    private val identity: HostAgentClient,
    private val clock: Clock = Clock.systemUTC(),
)
{
    suspend operator fun invoke(
        idRaw: String,
        agentUrlRaw: String,
        agentKeyRaw: String = "",
        name: String = "",
        panelUrlRaw: String = "",
    ): UpdateServerResult
    {
        val id = ServerId.parse(idRaw.trim()) ?: return UpdateServerResult.NotFound
        val existing = servers.find(id) ?: return UpdateServerResult.NotFound
        if (existing.isDeleted())
        {
            return UpdateServerResult.NotFound
        }
        if (existing.isRemoving())
        {
            return UpdateServerResult.Removing
        }
        val agentUrl = agentUrlRaw.trim().trimEnd('/')
        if (agentUrl.isEmpty())
        {
            return UpdateServerResult.AgentUrlRequired
        }
        val token = agentKeyRaw.trim().ifEmpty { existing.agentKey.trim() }
        if (token.isEmpty())
        {
            return UpdateServerResult.AgentKeyRequired
        }
        val panelUrl = panelUrlRaw.trim().trimEnd('/')
        if (panelUrl.isEmpty())
        {
            return UpdateServerResult.PanelUrlRequired
        }
        when (val enrolled = enroll.enroll(agentUrl, token, panelUrl, id.value))
        {
            EnrollHostAgentResult.Ok -> Unit
            EnrollHostAgentResult.Unauthorized ->
                return UpdateServerResult.InvalidAgentKey(agentUrl)
            EnrollHostAgentResult.PanelUnreachable ->
                return UpdateServerResult.PanelUnreachable(agentUrl, panelUrl)
            is EnrollHostAgentResult.Failed ->
                return UpdateServerResult.EnrollFailed(agentUrl, enrolled.detail)
        }
        val got = identity.identity(agentUrl, token)
        val now = Instant.now(clock).toString()
        val updated = existing.copy(
            name = name.trim().ifEmpty { existing.name },
            agentUrl = agentUrl,
            agentKey = token,
            os = got?.os?.trim().orEmpty().ifEmpty { existing.os },
            arch = got?.arch?.trim().orEmpty().ifEmpty { existing.arch },
            osPretty = got?.osPretty?.trim().orEmpty().ifEmpty { existing.osPretty },
            agentVersion = got?.version?.trim().orEmpty().ifEmpty { existing.agentVersion },
            updatedAt = now,
        )
        servers.update(updated)
        return UpdateServerResult.Updated(updated)
    }
}
