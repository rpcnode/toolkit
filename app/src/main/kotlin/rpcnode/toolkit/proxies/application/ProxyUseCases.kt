package rpcnode.toolkit.proxies.application

import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.proxies.domain.model.Proxy
import rpcnode.toolkit.proxies.domain.model.ProxyCheck
import rpcnode.toolkit.proxies.domain.model.ProxyCheckRunner
import rpcnode.toolkit.proxies.domain.model.ProxyId
import rpcnode.toolkit.proxies.domain.model.ProxyUrl
import rpcnode.toolkit.proxies.domain.repository.ProxyRepository

/** One list row: the URL is masked, [nodes] are the names of nodes using it. */
data class ProxyView(val id: String, val name: String, val url: String, val nodes: List<String>)

class ListProxiesUseCase(
    private val proxies: ProxyRepository,
    private val nodes: NodeRepository,
)
{
    suspend operator fun invoke(): List<ProxyView>
    {
        val byProxy = nodes.list().filter { it.proxyId.isNotBlank() }.groupBy { it.proxyId }
        return proxies.list().map { p ->
            ProxyView(
                id = p.id.value,
                name = p.name,
                url = p.url.masked,
                nodes = byProxy[p.id.value].orEmpty().map { it.name },
            )
        }
    }
}

sealed interface SaveProxyResult
{
    data class Ok(val proxy: Proxy) : SaveProxyResult
    data class InvalidUrl(val reason: String) : SaveProxyResult
    /** The proxy answered nothing useful: dead, wrong port/credentials, or it blocks HTTPS tunnelling. */
    data class Unreachable(val reason: String) : SaveProxyResult
    data object NotFound : SaveProxyResult
}

/**
 * Create ([id] null) or edit a proxy. The list shows masked URLs, so an edit that sends the masked URL back
 * unchanged (or a blank one) keeps the stored URL and its password.
 */
class SaveProxyUseCase(
    private val proxies: ProxyRepository,
    private val checker: ProxyCheckRunner? = null,
)
{
    suspend operator fun invoke(
        id: String?,
        name: String,
        url: String,
        skipCheck: Boolean = false,
        /** Check from this host's agent instead of the panel. */
        serverId: String? = null,
    ): SaveProxyResult
    {
        val existing = id?.let { ProxyId.parse(it) }?.let { proxies.find(it) ?: return SaveProxyResult.NotFound }
        val keepUrl = existing != null && (url.isBlank() || url.trim() == existing.url.masked)
        val parsedUrl = if (keepUrl)
        {
            existing!!.url
        }
        else
        {
            when (val parsed = ProxyUrl.parse(url))
            {
                is ProxyUrl.Parse.Ok -> parsed.url
                ProxyUrl.Parse.Empty -> return SaveProxyResult.InvalidUrl("url is required")
                is ProxyUrl.Parse.Invalid -> return SaveProxyResult.InvalidUrl(parsed.reason)
            }
        }
        if (!skipCheck && checker != null)
        {
            when (val check = checker.run(parsedUrl, serverId))
            {
                is ProxyCheck.Failed -> return SaveProxyResult.Unreachable(check.reason)
                is ProxyCheck.Ok -> Unit
            }
        }
        val proxy = Proxy(
            id = existing?.id ?: ProxyId.generate(),
            name = name.trim().ifEmpty { parsedUrl.masked.substringAfter('@').substringAfter("://") },
            url = parsedUrl,
        )
        proxies.save(proxy)
        return SaveProxyResult.Ok(proxy)
    }
}

sealed interface DeleteProxyResult
{
    data object Ok : DeleteProxyResult
    data object NotFound : DeleteProxyResult
    /** Still selected on these nodes — switch them first. */
    data class InUse(val nodes: List<String>) : DeleteProxyResult
}

class DeleteProxyUseCase(
    private val proxies: ProxyRepository,
    private val nodes: NodeRepository,
)
{
    suspend operator fun invoke(id: String): DeleteProxyResult
    {
        val pid = ProxyId.parse(id) ?: return DeleteProxyResult.NotFound
        val users = nodes.list().filter { it.proxyId == pid.value }
        if (users.isNotEmpty())
        {
            return DeleteProxyResult.InUse(users.map { it.name })
        }
        return if (proxies.delete(pid)) DeleteProxyResult.Ok else DeleteProxyResult.NotFound
    }
}

/** The proxy URL a node's host traffic goes through (Start, Snapshot), or "" for direct. */
class ResolveNodeProxyUrl(private val proxies: ProxyRepository)
{
    suspend operator fun invoke(node: Node): String
    {
        val id = ProxyId.parse(node.proxyId) ?: return ""
        return proxies.find(id)?.url?.value.orEmpty()
    }
}

/** "Test" button: check a URL (or a saved proxy, when the masked/blank URL is sent back) without saving it. */
class TestProxyUseCase(
    private val proxies: ProxyRepository,
    private val checker: ProxyCheckRunner,
)
{
    suspend operator fun invoke(id: String?, url: String, serverId: String? = null): ProxyCheck
    {
        val existing = id?.let { ProxyId.parse(it) }?.let { proxies.find(it) }
        val target = if (existing != null && (url.isBlank() || url.trim() == existing.url.masked))
        {
            existing.url
        }
        else
        {
            when (val parsed = ProxyUrl.parse(url))
            {
                is ProxyUrl.Parse.Ok -> parsed.url
                ProxyUrl.Parse.Empty -> return ProxyCheck.Failed("url is required")
                is ProxyUrl.Parse.Invalid -> return ProxyCheck.Failed(parsed.reason)
            }
        }
        return checker.run(target, serverId)
    }
}
