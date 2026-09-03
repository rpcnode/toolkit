package rpcnode.toolkit.networks.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.shared.infrastructure.log.HttpIoLog

/** HEAD probe — true only on HTTP 2xx (403/404 are absent). */
fun interface SnapshotPresenceProbe
{
    suspend fun present(url: String): Boolean
}

class HttpSnapshotPresenceProbe(
    private val timeout: Duration = Duration.ofSeconds(6),
) : SnapshotPresenceProbe
{
    override suspend fun present(url: String): Boolean = withContext(Dispatchers.IO) {
        val target = url.trim()
        if (target.isEmpty())
        {
            return@withContext false
        }
        val started = System.nanoTime()
        try
        {
            val client = HttpClient.newBuilder().connectTimeout(timeout).build()
            val req = HttpRequest.newBuilder(URI(target))
                .timeout(timeout)
                .header("User-Agent", "rpcnode-server-snapshot-probe")
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
            HttpIoLog.outbound("HEAD", target, resp.statusCode(), (System.nanoTime() - started) / 1_000_000)
            resp.statusCode() in 200 until 300
        }
        catch (_: Exception)
        {
            HttpIoLog.outbound("HEAD", target, 0, (System.nanoTime() - started) / 1_000_000)
            false
        }
    }
}
