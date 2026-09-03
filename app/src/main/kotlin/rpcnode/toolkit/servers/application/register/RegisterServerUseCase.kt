package rpcnode.toolkit.servers.application.register

import java.time.Clock
import java.time.Instant
import rpcnode.toolkit.servers.application.probe.EnrollHostAgent
import rpcnode.toolkit.servers.application.probe.EnrollHostAgentResult
import rpcnode.toolkit.servers.application.probe.HostAgentClient
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface RegisterServerResult
{
    data class Created(val server: Server) : RegisterServerResult
    data object AgentUrlRequired : RegisterServerResult
    data object AgentKeyRequired : RegisterServerResult
    data object PanelUrlRequired : RegisterServerResult
    data class EnrollFailed(val agentUrl: String, val detail: String = "") : RegisterServerResult
    data class PanelUnreachable(val agentUrl: String, val panelUrl: String) : RegisterServerResult
    data class InvalidAgentKey(val agentUrl: String) : RegisterServerResult
}

class RegisterServerUseCase(
    private val servers: ServerRepository,
    private val enroll: EnrollHostAgent,
    private val identity: HostAgentClient,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> ServerId = { ServerId.generate() },
)
{
    suspend operator fun invoke(
        agentUrlRaw: String,
        agentKey: String = "",
        name: String = "",
        os: String = "",
        arch: String = "",
        osPretty: String = "",
        panelUrlRaw: String = "",
    ): RegisterServerResult
    {
        val agentUrl = agentUrlRaw.trim().trimEnd('/')
        if (agentUrl.isEmpty())
        {
            return RegisterServerResult.AgentUrlRequired
        }
        val token = agentKey.trim()
        if (token.isEmpty())
        {
            return RegisterServerResult.AgentKeyRequired
        }
        val panelUrl = panelUrlRaw.trim().trimEnd('/')
        if (panelUrl.isEmpty())
        {
            return RegisterServerResult.PanelUrlRequired
        }
        val now = Instant.now(clock).toString()
        val id = newId()
        when (val enrolled = enroll.enroll(agentUrl, token, panelUrl, id.value))
        {
            EnrollHostAgentResult.Ok -> Unit
            EnrollHostAgentResult.Unauthorized ->
                return RegisterServerResult.InvalidAgentKey(agentUrl)
            EnrollHostAgentResult.PanelUnreachable ->
                return RegisterServerResult.PanelUnreachable(agentUrl, panelUrl)
            is EnrollHostAgentResult.Failed ->
                return RegisterServerResult.EnrollFailed(agentUrl, enrolled.detail)
        }
        val got = identity.identity(agentUrl, token)
        val server = Server(
            id = id,
            name = name.trim().ifEmpty { id.value },
            agentUrl = agentUrl,
            agentKey = token,
            os = os.trim().ifEmpty { got?.os.orEmpty() },
            arch = arch.trim().ifEmpty { got?.arch.orEmpty() },
            osPretty = osPretty.trim().ifEmpty { got?.osPretty.orEmpty() },
            agentVersion = got?.version.orEmpty().trim(),
            createdAt = now,
            updatedAt = now,
        )
        servers.insert(server)
        return RegisterServerResult.Created(server)
    }
}
