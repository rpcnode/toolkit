package rpcnode.toolkit.settings.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.settings.application.get.UrlProbe
import rpcnode.toolkit.shared.infrastructure.log.HttpIoLog

class HttpUrlProbe(
    private val timeout: Duration = Duration.ofSeconds(4),
) : UrlProbe
{
    override suspend fun reachable(url: String): Boolean = withContext(Dispatchers.IO) {
        for (cand in candidates(url))
        {
            if (probeOnce(cand))
            {
                return@withContext true
            }
        }
        false
    }

    private fun probeOnce(raw: String): Boolean
    {
        val started = System.nanoTime()
        return try
        {
            val client = HttpClient.newBuilder().connectTimeout(timeout).build()
            val req = HttpRequest.newBuilder(URI(raw))
                .timeout(timeout)
                .header("User-Agent", "rpcnode-server-probe")
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
            HttpIoLog.outbound("GET", raw, resp.statusCode(), elapsedMs(started))
            resp.statusCode() in 200 until 400
        }
        catch (_: Exception)
        {
            HttpIoLog.outbound("GET", raw, 0, elapsedMs(started))
            false
        }
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private fun candidates(raw: String): List<String>
    {
        val out = mutableListOf(raw)
        val rew = raw
            .replace("://127.0.0.1", "://host.docker.internal")
            .replace("://localhost", "://host.docker.internal")
        if (rew != raw)
        {
            out += rew
        }
        return out
    }
}
