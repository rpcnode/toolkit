package rpcnode.toolkit.chains.cometbft.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.domain.model.SnapshotArchive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Where one snapshot type of one env comes from. The URL is looked up at job start: providers keep one file. */
sealed interface CometbftSnapshotSource
{
    /** Body of [url] is the archive URL (Osmosis `/latest`). */
    data class LatestText(val url: String) : CometbftSnapshotSource

    /** `{"snapshot":{"url":…}}` (Injective `status.json`); the first URL that answers wins. */
    data class StatusJson(val urls: List<String>) : CometbftSnapshotSource

    /** Polkachu: the snapshot link is scraped from the chain's snapshot page. */
    data class PolkachuPage(val pageUrl: String, val slug: String, val testnet: Boolean = false) : CometbftSnapshotSource

    data class Fixed(val url: String) : CometbftSnapshotSource
}

/** `SnapshotResolver` of a CometBFT chain: `(env, typeId)` → [CometbftSnapshotSource] → live archive URL + size. */
class CometbftSnapshotResolver(
    private val sources: Map<Pair<String, String>, CometbftSnapshotSource>,
    private val defaultType: String = "pruned",
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(15))),
    private val contentLength: suspend (String) -> Long? = ::headLength,
) : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        val type = typeId.trim().lowercase().ifEmpty { defaultType }
        val source = sources[env.value to type] ?: return null
        val url = discover(source) ?: return null
        return SnapshotArchive(url = url, streamUnpack = true, sizeBytes = contentLength(url))
    }

    private suspend fun discover(source: CometbftSnapshotSource): String? = when (source)
    {
        is CometbftSnapshotSource.Fixed -> source.url
        is CometbftSnapshotSource.LatestText -> parseLatestText(http.getText(source.url))
        is CometbftSnapshotSource.StatusJson ->
            source.urls.firstNotNullOfOrNull { parseStatusJson(http.getText(it)) }
        is CometbftSnapshotSource.PolkachuPage ->
            parsePolkachu(http.getText(source.pageUrl), source.slug, source.testnet)
    }

    companion object
    {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseLatestText(body: String?): String? =
            body?.lineSequence()?.map { it.trim() }
                ?.firstOrNull { it.startsWith("http", ignoreCase = true) && it.contains(".tar") }

        fun parseStatusJson(body: String?): String? =
            runCatching {
                json.parseToJsonElement(body ?: return null).jsonObject["snapshot"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.trim()?.takeIf { it.startsWith("http") }

        fun parsePolkachu(page: String?, slug: String, testnet: Boolean): String?
        {
            if (page.isNullOrBlank()) return null
            val prefix = if (testnet) "testnet-snapshots" else "snapshots"
            val re = Regex("""https://snapshots\.polkachu\.com/$prefix/${Regex.escape(slug)}/${Regex.escape(slug)}_\d+\.tar\.lz4""")
            return re.findAll(page).map { it.value }.maxByOrNull { it.substringAfterLast('_').substringBefore('.').toLongOrNull() ?: 0L }
        }

        /** HEAD `Content-Length`; null when the server will not say. */
        suspend fun headLength(url: String): Long? = withContext(Dispatchers.IO) {
            runCatching {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build()
                val req = HttpRequest.newBuilder(URI(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(10)).build()
                val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
                resp.headers().firstValueAsLong("content-length").let { if (it.isPresent) it.asLong else null }
            }.getOrNull()?.takeIf { it > 0 }
        }
    }
}
