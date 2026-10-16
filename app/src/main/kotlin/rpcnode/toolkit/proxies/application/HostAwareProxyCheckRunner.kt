package rpcnode.toolkit.proxies.application

import rpcnode.toolkit.proxies.domain.model.CheckProxyOnHost
import rpcnode.toolkit.proxies.domain.model.ProxyCheck
import rpcnode.toolkit.proxies.domain.model.ProxyCheckRunner
import rpcnode.toolkit.proxies.domain.model.ProxyChecker
import rpcnode.toolkit.proxies.domain.model.ProxyUrl
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

/**
 * A proxy is often allow-listed for the server's IP only, so "reachable from the panel" says little: with a
 * [serverId] the connect is made by that host's agent, which is where the node's traffic really leaves from.
 */
class HostAwareProxyCheckRunner(
    private val panel: ProxyChecker,
    private val servers: ServerRepository,
    private val onHost: CheckProxyOnHost,
) : ProxyCheckRunner
{
    override suspend fun run(url: ProxyUrl, serverId: String?): ProxyCheck
    {
        val id = serverId?.trim().orEmpty()
        if (id.isEmpty())
        {
            return panel.check(url)
        }
        val server = ServerId.parse(id)?.let { servers.find(it) }?.takeIf { it.isActive() }
            ?: return ProxyCheck.Failed("server not found")
        val result = onHost.check(server.agentUrl, server.agentKey, url)
            ?: return ProxyCheck.Failed("host agent on ${server.displayName()} did not answer")
        return when (result)
        {
            is ProxyCheck.Ok -> result
            is ProxyCheck.Failed -> ProxyCheck.Failed("from ${server.displayName()}: ${result.reason}")
        }
    }
}
