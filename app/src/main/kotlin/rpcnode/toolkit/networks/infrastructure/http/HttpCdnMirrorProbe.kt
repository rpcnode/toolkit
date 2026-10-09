package rpcnode.toolkit.networks.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.networks.application.snapshot.CdnMirrorProbe
import rpcnode.toolkit.shared.infrastructure.log.HttpIoLog

class HttpCdnMirrorProbe(
    private val timeout: Duration = Duration.ofSeconds(4),
) : CdnMirrorProbe
{
    override suspend fun versionText(url: String): String? = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try
        {
            val client = HttpClient.newBuilder().connectTimeout(timeout).build()
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .header("User-Agent", "rpcnode-server-cdn-probe")
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            HttpIoLog.outbound("GET", url, resp.statusCode(), elapsedMs(started))
            if (resp.statusCode() !in 200 until 300)
            {
                return@withContext null
            }
            resp.body()?.trim()?.ifEmpty { null }
        }
        catch (_: Exception)
        {
            HttpIoLog.outbound("GET", url, 0, elapsedMs(started))
            null
        }
    }

    override suspend fun archivePresent(url: String): Boolean = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        try
        {
            val client = HttpClient.newBuilder().connectTimeout(timeout).build()
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .header("User-Agent", "rpcnode-server-cdn-probe")
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
            HttpIoLog.outbound("HEAD", url, resp.statusCode(), elapsedMs(started))
            resp.statusCode() in 200 until 300
        }
        catch (_: Exception)
        {
            HttpIoLog.outbound("HEAD", url, 0, elapsedMs(started))
            false
        }
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000
}
