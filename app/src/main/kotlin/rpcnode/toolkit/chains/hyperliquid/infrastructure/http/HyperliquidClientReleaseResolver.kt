package rpcnode.toolkit.chains.hyperliquid.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.hyperliquid.infrastructure.HyperliquidClusters
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Rolling CDN hl-visor version from HEAD Last-Modified + ETag
 * (`yyyy-MM-dd-<etag8>`, matching Go client-sync pins).
 */
class HyperliquidClientReleaseResolver(
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build(),
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        val cluster = HyperliquidClusters.lookup(env.value)
        return headVersion(cluster.binaryUrl)?.let { pin ->
            ClientRelease(
                version = pin,
                tag = pin,
                sourceLabel = SOURCE[cluster.env] ?: cluster.binaryUrl,
            )
        }
    }

    fun headVersion(binaryUrl: String): String?
    {
        return try
        {
            val req = HttpRequest.newBuilder(URI(binaryUrl))
                .timeout(Duration.ofSeconds(8))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.discarding())
            if (resp.statusCode() !in 200..299)
            {
                return null
            }
            val etag = resp.headers().firstValue("etag").orElse("")
                .trim().trim('"').take(8)
            val lastMod = resp.headers().firstValue("last-modified").orElse("")
            val day = parseHttpDate(lastMod)
            when
            {
                day != null && etag.isNotEmpty() -> "$day-$etag"
                day != null -> day
                etag.isNotEmpty() -> etag
                else -> null
            }
        }
        catch (_: Exception)
        {
            null
        }
    }

    private fun parseHttpDate(raw: String): String?
    {
        if (raw.isBlank())
        {
            return null
        }
        return try
        {
            val zdt = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
            zdt.toLocalDate().toString()
        }
        catch (_: Exception)
        {
            // Some CDNs use non-RFC variants; keep a coarse fallback.
            val m = Regex(
                """(\d{1,2})\s+([A-Za-z]{3})\s+(\d{4})""",
            ).find(raw) ?: return null
            val day = m.groupValues[1].padStart(2, '0')
            val mon = MONTH[m.groupValues[2].lowercase(Locale.ROOT)] ?: return null
            val year = m.groupValues[3]
            "$year-$mon-$day"
        }
    }

    companion object
    {
        private val SOURCE = mapOf(
            "mainnet" to "binaries.hyperliquid.xyz",
            "testnet" to "binaries.hyperliquid-testnet.xyz",
        )
        private val MONTH = mapOf(
            "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04",
            "may" to "05", "jun" to "06", "jul" to "07", "aug" to "08",
            "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12",
        )
    }
}
