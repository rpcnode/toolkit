package rpcnode.toolkit.servers.application.probe

sealed interface ProbeHostAgentResult
{
    data class Ok(val identity: HostAgentIdentity, val agentUrl: String) : ProbeHostAgentResult
    data object MissingUrl : ProbeHostAgentResult
    data object MissingToken : ProbeHostAgentResult
    data class InvalidToken(val agentUrl: String) : ProbeHostAgentResult
    data class Unreachable(val agentUrl: String, val detail: String = "") : ProbeHostAgentResult
}

class ProbeHostAgentUseCase(
    private val client: HostAgentClient,
)
{
    suspend operator fun invoke(agentUrlRaw: String, tokenRaw: String): ProbeHostAgentResult
    {
        val agentUrl = agentUrlRaw.trim().trimEnd('/')
        if (agentUrl.isEmpty())
        {
            return ProbeHostAgentResult.MissingUrl
        }
        val token = tokenRaw.trim()
        if (token.isEmpty())
        {
            return ProbeHostAgentResult.MissingToken
        }
        return when (val got = client.lookup(agentUrl, token))
        {
            is HostAgentLookup.Ok ->
                ProbeHostAgentResult.Ok(identity = got.identity, agentUrl = agentUrl)
            is HostAgentLookup.Unauthorized ->
                ProbeHostAgentResult.InvalidToken(agentUrl)
            is HostAgentLookup.Failed ->
                ProbeHostAgentResult.Unreachable(agentUrl, got.detail)
        }
    }
}
