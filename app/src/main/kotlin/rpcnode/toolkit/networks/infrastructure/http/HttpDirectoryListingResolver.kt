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

/**
 * Seam between [HttpDirectoryListingResolver] and per-network snapshot resolvers that happen
 * to share an Apache/nginx dated-folder listing. Other networks parse their mirrors themselves
 * and never come through here.
 */
fun interface DirectoryListingResolver
{
    suspend fun latestArchiveUrl(mirrorRootUrl: String, entryPattern: Regex, archiveName: String): String?
}

/**
 * Network-agnostic mechanic for mirrors that publish an HTML directory listing with one
 * dated/versioned entry per rotation (Apache/nginx autoindex style): fetch the root, find the
 * newest entry matching [entryPattern], build the archive URL under it. Which pattern and archive
 * filename apply is knowledge that belongs to the network using this (e.g. TRON's
 * `backup[0-9]{8}` folders) — this class only knows HTTP and caching, nothing about any one
 * network's layout, so it can be reused by any per-network resolver with a similar mirror shape.
 *
 * A root that already points straight at an archive (`.tgz`/`.tar.gz`) is returned unchanged — no
 * listing to scrape. Results are cached per (root, pattern, archive name) for [cacheTtl]: these
 * mirrors typically rotate at most once a day, so re-scraping on every call would just be load
 * with no benefit.
 */
class HttpDirectoryListingResolver(
    private val cacheTtl: Duration = Duration.ofHours(6),
    private val requestTimeout: Duration = Duration.ofSeconds(5),
) : DirectoryListingResolver
{
    private val log = LoggerFactory.getLogger(HttpDirectoryListingResolver::class.java)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class CacheEntry(val url: String?, val fetchedAt: Instant)

    override suspend fun latestArchiveUrl(mirrorRootUrl: String, entryPattern: Regex, archiveName: String): String?
    {
        val root = mirrorRootUrl.trim()
        if (root.isEmpty())
        {
            return null
        }
        if (root.endsWith(".tgz") || root.endsWith(".tar.gz"))
        {
            return root
        }
        val base = root.trimEnd('/')
        val cacheKey = "$base|${entryPattern.pattern}|$archiveName"

        val cached = cache[cacheKey]
        if (cached != null && Instant.now().isBefore(cached.fetchedAt.plus(cacheTtl)))
        {
            return cached.url
        }

        val resolved = withContext(Dispatchers.IO) { scrapeLatestEntryUrl(base, entryPattern, archiveName) }
        cache[cacheKey] = CacheEntry(resolved ?: cached?.url, Instant.now())
        return resolved ?: cached?.url
    }

    private fun scrapeLatestEntryUrl(base: String, entryPattern: Regex, archiveName: String): String?
    {
        try
        {
            val client = HttpClient.newBuilder().connectTimeout(requestTimeout).build()
            val request = HttpRequest.newBuilder(URI("$base/")).timeout(requestTimeout).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200 until 300)
            {
                log.warn("mirror {}: HTTP {}", base, response.statusCode())
                return null
            }
            val latest = entryPattern.findAll(response.body()).map { it.value }.maxOrNull() ?: return null
            return "$base/$latest/$archiveName"
        }
        catch (e: CancellationException)
        {
            throw e
        }
        catch (e: Exception)
        {
            log.warn("mirror {}: {}", base, e.message)
            return null
        }
    }
}
