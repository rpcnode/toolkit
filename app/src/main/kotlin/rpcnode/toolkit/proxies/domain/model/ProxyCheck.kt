package rpcnode.toolkit.proxies.domain.model

/** Result of a live connect through a proxy. */
sealed interface ProxyCheck
{
    data class Ok(val latencyMs: Long, val target: String) : ProxyCheck
    data class Failed(val reason: String) : ProxyCheck
}

/** Opens a real HTTPS connection through [url] so a dead, blocked or mistyped proxy is caught before a node uses it. */
fun interface ProxyChecker
{
    suspend fun check(url: ProxyUrl): ProxyCheck
}

/** Runs a proxy check from the panel ([serverId] blank) or from a host through its agent. */
fun interface ProxyCheckRunner
{
    suspend fun run(url: ProxyUrl, serverId: String?): ProxyCheck
}

/** Asks a host agent to connect through [url]; null = the agent did not answer. */
fun interface CheckProxyOnHost
{
    suspend fun check(agentUrl: String, token: String, url: ProxyUrl): ProxyCheck?
}
