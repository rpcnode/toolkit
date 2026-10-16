package rpcnode.toolkit.proxies.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.proxies.domain.model.ProxyCheck
import rpcnode.toolkit.proxies.domain.model.ProxyChecker
import rpcnode.toolkit.proxies.domain.model.ProxyUrl
import rpcnode.toolkit.shared.infrastructure.http.HttpProxy

/**
 * HEAD an HTTPS endpoint through the proxy. Any HTTP status counts as reachable (the proxy tunnelled the TLS
 * connection); 407 means wrong credentials, and a connect/TLS failure means the proxy itself is down or blocks CONNECT.
 */
class HttpProxyChecker(
    private val targets: List<String> = listOf("https://api.github.com", "https://www.cloudflare.com"),
    private val timeout: Duration = Duration.ofSeconds(10),
) : ProxyChecker
{
    override suspend fun check(url: ProxyUrl): ProxyCheck = withContext(Dispatchers.IO) {
        var last = "no check target"
        for (target in targets)
        {
            val started = System.nanoTime()
            try
            {
                val client = HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .apply { HttpProxy.apply(this, url.value) }
                    .build()
                val req = HttpRequest.newBuilder(URI(target))
                    .timeout(timeout)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build()
                val status = client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode()
                if (status == 407)
                {
                    return@withContext ProxyCheck.Failed("proxy rejected the login (407) — check user and password")
                }
                return@withContext ProxyCheck.Ok((System.nanoTime() - started) / 1_000_000, target)
            }
            catch (e: Exception)
            {
                last = "$target via proxy: ${e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName}"
            }
        }
        ProxyCheck.Failed(last)
    }
}
