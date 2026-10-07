package rpcnode.toolkit.networks.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import rpcnode.toolkit.networks.application.snapshot.SnapshotSizeProbe

/**
 * HEAD (then a 1-byte ranged GET if the host ignores HEAD) for Content-Length.
 * Cached per URL so a panel refresh does not hammer the mirror. Networks whose
 * size is not a single-file Content-Length must not use this.
 */
class HttpSnapshotSizeProbe(
    private val cacheTtl: Duration = Duration.ofMinutes(30),
    private val requestTimeout: Duration = Duration.ofSeconds(6),
) : SnapshotSizeProbe
{
    private val log = LoggerFactory.getLogger(HttpSnapshotSizeProbe::class.java)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(val bytes: Long?, val fetchedAt: Instant)

    override suspend fun bytes(url: String): Long?
    {
        val target = url.trim()
        if (target.isEmpty())
        {
            return null
        }

        val cached = cache[target]
        if (cached != null && cached.bytes != null && Instant.now().isBefore(cached.fetchedAt.plus(cacheTtl)))
        {
            return cached.bytes
        }

        val probed = withContext(Dispatchers.IO) { probe(target) }
        if (probed != null)
        {
            cache[target] = CacheEntry(probed, Instant.now())
        }
        return probed ?: cached?.bytes
    }

    private fun probe(url: String): Long?
    {
        val client = HttpClient.newBuilder().connectTimeout(requestTimeout).build()
        return tryHead(client, url) ?: tryRangeGet(client, url)
    }

    private fun tryHead(client: HttpClient, url: String): Long? = send(client, url, "HEAD", emptyMap())

    private fun tryRangeGet(client: HttpClient, url: String): Long? =
        send(client, url, "GET", mapOf("Range" to "bytes=0-0"))

    private fun send(client: HttpClient, url: String, method: String, headers: Map<String, String>): Long?
    {
        try
        {
            val builder = HttpRequest.newBuilder(URI(url)).timeout(requestTimeout).method(method, HttpRequest.BodyPublishers.noBody())
            for ((name, value) in headers)
            {
                builder.header(name, value)
            }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() !in 200 until 400)
            {
                return null
            }
            val range = parseContentRangeTotal(response.headers().firstValue("Content-Range").orElse(null))
            if (range != null)
            {
                return range
            }
            val length = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            return length.takeIf { it > 0 }
        }
        catch (e: CancellationException)
        {
            throw e
        }
        catch (e: Exception)
        {
            log.warn("size {}: {}", url, e.message)
            return null
        }
    }

    companion object
    {
        private val CONTENT_RANGE_TOTAL = Regex("""bytes\s+\d+-\d+/(\d+)""")

        internal fun parseContentRangeTotal(header: String?): Long?
        {
            val raw = header?.trim().orEmpty()
            if (raw.isEmpty())
            {
                return null
            }
            val n = CONTENT_RANGE_TOTAL.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            return n.takeIf { it > 0 }
        }
    }
}
