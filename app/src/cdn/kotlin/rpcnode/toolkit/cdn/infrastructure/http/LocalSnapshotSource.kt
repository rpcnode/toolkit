package rpcnode.toolkit.cdn.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rpcnode.toolkit.cdn.application.sync.OfficialSnapshot
import rpcnode.toolkit.cdn.application.sync.SnapshotMirrorKind
import rpcnode.toolkit.cdn.application.sync.SnapshotSource
import rpcnode.toolkit.cdn.application.sync.SnapshotTarget
import rpcnode.toolkit.cdn.application.targets.SnapshotTargetStore
import rpcnode.toolkit.cdn.domain.model.MirrorSpec
import rpcnode.toolkit.cdn.infrastructure.catalog.EmbeddedMirrorCatalog

/**
 * Local targets + official upstream discovery (no panel).
 * [listTargets] returns null only when the targets file exists but cannot be parsed.
 */
class LocalSnapshotSource(
    private val targets: SnapshotTargetStore,
    private val catalog: EmbeddedMirrorCatalog,
    private val timeout: Duration = Duration.ofSeconds(30),
    private val clock: Clock = Clock.systemUTC(),
    private val datedLookbackDays: Int = 60,
) : SnapshotSource
{
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listTargets(): List<SnapshotTarget>? = try
    {
        targets.list()
    }
    catch (_: Exception)
    {
        null
    }

    override suspend fun officialSnapshot(target: SnapshotTarget): OfficialSnapshot? =
        withContext(Dispatchers.IO) {
            val spec = catalog.find(target.network, target.env, target.type) ?: return@withContext null
            when (spec.discover)
            {
                "base_api" -> fromBaseApi(target, spec)
                else -> fromArchiveMirror(target, spec)
            }
        }

    private fun fromArchiveMirror(target: SnapshotTarget, spec: MirrorSpec): OfficialSnapshot?
    {
        val url = resolveUrl(spec) ?: return null
        val version = versionFromUrl(url) ?: return null
        val filename = filenameFromUrl(url) ?: spec.filename
        return OfficialSnapshot(
            network = target.network,
            env = target.env,
            type = target.type,
            url = url,
            version = version,
            filename = filename,
            sizeBytes = sizeBytes(url),
            kind = SnapshotMirrorKind.ARCHIVE_FILE,
        )
    }

    private fun fromBaseApi(target: SnapshotTarget, spec: MirrorSpec): OfficialSnapshot?
    {
        val body = getText(spec.mirror.trim()) ?: return null
        val tip = parseBaseApiTip(body, network = target.env, profile = target.type) ?: return null
        val version = BaseManifestMirror.versionFromManifestUrl(tip.manifestUrl)
            ?: tip.timestamp?.toString()
            ?: return null
        return OfficialSnapshot(
            network = target.network,
            env = target.env,
            type = target.type,
            url = tip.manifestUrl,
            version = version,
            filename = spec.filename.ifBlank { "manifest.json" },
            sizeBytes = tip.sizeBytes,
            kind = SnapshotMirrorKind.BASE_MANIFEST,
        )
    }

    /**
     * Pick the API row whose `network` and `profile` match the CDN target env/type.
     * Exposed for unit tests with fixture JSON.
     */
    fun parseBaseApiTip(body: String, network: String, profile: String): BaseApiTip?
    {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray
            ?: return null
        val wantNet = network.trim().lowercase()
        val wantProfile = profile.trim().lowercase()
        for (el in root)
        {
            val obj = el as? JsonObject ?: continue
            val net = obj["network"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            val prof = obj["profile"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            if (net != wantNet || prof != wantProfile)
            {
                continue
            }
            val manifestUrl = obj["manifestUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (manifestUrl.isEmpty())
            {
                continue
            }
            val size = obj["size"]?.jsonPrimitive?.longOrNull
            val timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull
            return BaseApiTip(manifestUrl = manifestUrl, sizeBytes = size, timestamp = timestamp)
        }
        return null
    }

    data class BaseApiTip(
        val manifestUrl: String,
        val sizeBytes: Long?,
        val timestamp: Long?,
    )

    private fun resolveUrl(spec: MirrorSpec): String?
    {
        return when (spec.discover)
        {
            "dated" -> latestDatedUrl(spec)
            else -> latestListingUrl(spec)
        }
    }

    private fun latestListingUrl(spec: MirrorSpec): String?
    {
        val root = spec.mirror.trim().let { if (it.endsWith("/")) it else "$it/" }
        if (root.endsWith(".tgz") || root.endsWith(".tar.gz"))
        {
            return root.trimEnd('/')
        }
        val body = getText(root) ?: return null
        val pattern = Pattern.compile("""href=["']?(backup\d{8})/?["']?""", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(body)
        var latest: String? = null
        while (matcher.find())
        {
            val stamp = matcher.group(1)
            if (latest == null || stamp > latest)
            {
                latest = stamp
            }
        }
        if (latest == null)
        {
            return null
        }
        return "$root$latest/${spec.filename}"
    }

    private fun latestDatedUrl(spec: MirrorSpec): String?
    {
        val root = spec.mirror.trim().trimEnd('/')
        val today = LocalDate.now(clock)
        for (offset in 0 until datedLookbackDays)
        {
            val stamp = today.minusDays(offset.toLong()).format(DAY_STAMP)
            val url = "$root/backup$stamp/${spec.filename}"
            if (present(url))
            {
                return url
            }
        }
        return null
    }

    private fun present(url: String): Boolean
    {
        return try
        {
            val client = HttpClient.newBuilder().connectTimeout(timeout).build()
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", "rpcnode-cdn")
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
            resp.statusCode() in 200 until 300
        }
        catch (_: Exception)
        {
            false
        }
    }

    private fun sizeBytes(url: String): Long?
    {
        return try
        {
            val client = HttpClient.newBuilder().connectTimeout(timeout).build()
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .header("User-Agent", "rpcnode-cdn")
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
            if (resp.statusCode() !in 200 until 300)
            {
                return null
            }
            resp.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
        }
        catch (_: Exception)
        {
            null
        }
    }

    private fun getText(url: String): String?
    {
        return try
        {
            val client = HttpClient.newBuilder().connectTimeout(timeout).build()
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .header("User-Agent", "rpcnode-cdn")
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200 until 300)
            {
                return null
            }
            resp.body()
        }
        catch (_: Exception)
        {
            null
        }
    }

    private fun versionFromUrl(url: String): String?
    {
        val parts = pathParts(url) ?: return null
        val backup = Regex("""backup\d{8}""")
        for (p in parts)
        {
            if (backup.matches(p))
            {
                return p
            }
        }
        return parts.dropLast(1).lastOrNull()
    }

    private fun filenameFromUrl(url: String): String? =
        pathParts(url)?.lastOrNull()?.takeIf { it.isNotEmpty() }

    private fun pathParts(url: String): List<String>?
    {
        val path = try
        {
            URI(url).path
        }
        catch (_: Exception)
        {
            return null
        }
        if (path.isNullOrBlank())
        {
            return null
        }
        return path.split('/').filter { it.isNotEmpty() }
    }

    companion object
    {
        private val DAY_STAMP = DateTimeFormatter.BASIC_ISO_DATE
    }
}
