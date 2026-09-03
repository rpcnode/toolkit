package rpcnode.toolkit.agent.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.application.enroll.ProbePanel

class HttpPanelReachabilityClient(
    private val timeout: Duration = Duration.ofSeconds(4),
    /** IDEA / `RPCNODE_DEV`: log the handshake GET (no token). */
    private val logRequests: Boolean = false,
) : ProbePanel
{
    private val log = LoggerFactory.getLogger(HttpPanelReachabilityClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun reachable(panelUrl: String): Boolean = withContext(Dispatchers.IO) {
        val url = "${panelUrl.trim().trimEnd('/')}/healthz"
        val started = System.nanoTime()
        try
        {
            val client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build()
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            val ms = Duration.ofNanos(System.nanoTime() - started).toMillis()
            val ok = resp.statusCode() in 200 until 300 && looksAlive(resp.body())
            if (ok)
            {
                if (logRequests)
                {
                    log.info("GET {} → {} {}ms", url, resp.statusCode(), ms)
                }
            }
            else
            {
                log.warn("GET {} → {} {}ms", url, resp.statusCode(), ms)
            }
            ok
        }
        catch (e: Exception)
        {
            val ms = Duration.ofNanos(System.nanoTime() - started).toMillis()
            log.warn("GET {} → fail {}ms: {}", url, ms, e.message)
            false
        }
    }

    private fun looksAlive(body: String): Boolean
    {
        if (body.isBlank())
        {
            return true
        }
        val ok = runCatching {
            json.parseToJsonElement(body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull
        }.getOrNull()
        return ok != false
    }
}
