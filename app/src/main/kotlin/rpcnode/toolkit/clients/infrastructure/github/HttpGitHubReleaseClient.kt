package rpcnode.toolkit.clients.infrastructure.github

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseAsset
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.GitHubTokenProvider
import rpcnode.toolkit.clients.application.ReleaseFilter
import rpcnode.toolkit.clients.application.assetGlobMatches

/** `GET /repos/{repo}/releases` — first non-draft, non-prerelease entry wins (releases are newest-first). */
class HttpGitHubReleaseClient(
    private val tokenProvider: GitHubTokenProvider,
    private val timeout: Duration = Duration.ofSeconds(10),
    private val apiBase: String = "https://api.github.com",
) : GitHubReleaseClient
{
    private val log = LoggerFactory.getLogger(HttpGitHubReleaseClient::class.java)

    override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease? =
        latestRelease(repo, tagPrefix, includePrerelease = false)

    override suspend fun latestRelease(
        repo: String,
        tagPrefix: String?,
        includePrerelease: Boolean,
    ): GitHubRelease? = latestRelease(repo, ReleaseFilter(tagPrefix, includePrerelease))

    override suspend fun latestRelease(repo: String, filter: ReleaseFilter): GitHubRelease? = withContext(Dispatchers.IO) {
        val perPage = if (filter.highestSemver || filter.tagSuffix != null) 60 else 20
        val releases = fetch(repo, perPage) ?: return@withContext null
        pickLatest(releases, filter)
    }

    override suspend fun releases(repo: String, perPage: Int): List<GitHubRelease> = withContext(Dispatchers.IO) {
        val raw = fetch(repo, perPage.coerceIn(1, 100)) ?: return@withContext emptyList()
        raw.mapNotNull { element ->
            val release = element.jsonObject
            if (release.isDraft()) return@mapNotNull null
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (tag.isEmpty()) return@mapNotNull null
            GitHubRelease(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                assets = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject.toAsset() },
                body = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                prerelease = release.isPrerelease(),
            )
        }
    }

    private suspend fun fetch(repo: String, perPage: Int): JsonArray?
    {
        return try
        {
            val client = HttpClient.newBuilder().connectTimeout(timeout).build()
            val reqBuilder = HttpRequest.newBuilder(URI("$apiBase/repos/$repo/releases?per_page=$perPage"))
                .timeout(timeout)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "rpcnode-server")
            val token = tokenProvider.current()
            if (!token.isNullOrBlank())
            {
                reqBuilder.header("Authorization", "Bearer $token")
            }
            val resp = client.send(reqBuilder.GET().build(), HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200 until 300)
            {
                log.warn("github releases {}: HTTP {}", repo, resp.statusCode())
                return null
            }
            Json.parseToJsonElement(resp.body()).jsonArray
        }
        catch (e: CancellationException)
        {
            throw e
        }
        catch (e: Exception)
        {
            log.warn("github releases {}: {}", repo, e.message)
            null
        }
    }

    private fun pickLatest(releases: JsonArray, filter: ReleaseFilter): GitHubRelease?
    {
        val candidates = mutableListOf<GitHubRelease>()
        for (raw in releases)
        {
            val release = raw.jsonObject
            if (release.isDraft() || (!filter.includePrerelease && release.isPrerelease())) continue
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (tag.isEmpty()) continue
            // Some projects publish alphas / previews as ordinary (non-prerelease) releases, e.g. bnb-chain/bsc v1.8.0-alpha.
            if (!filter.includePrerelease && PRE_RELEASE_TAG.containsMatchIn(tag)) continue
            if (filter.tagPrefix != null && !tag.startsWith(filter.tagPrefix)) continue
            if (filter.tagSuffix != null && !tag.endsWith(filter.tagSuffix)) continue
            if (filter.tagSuffix == null && NETWORK_SUFFIX_TAG.containsMatchIn(tag) && filter.highestSemver) continue
            val found = GitHubRelease(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                assets = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject.toAsset() },
                body = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            if (filter.requireAsset != null && found.assets.none { assetGlobMatches(filter.requireAsset, it.name) }) continue
            if (!filter.highestSemver)
            {
                return found
            }
            candidates += found
        }
        return candidates.maxWithOrNull { a, b -> compareSemver(a.version, b.version) }
    }

    private companion object
    {
        val PRE_RELEASE_TAG = Regex("(?i)[-.](alpha|beta|rc|preview)([-.]?\\d+)?$")

        /** `v10.4.0-mocha`, `v10.4.0-corto`: a testnet line published beside the mainnet tags. */
        val NETWORK_SUFFIX_TAG = Regex("(?i)\\d-(mocha|corto|arabica|testnet|devnet)(-.*)?$")

        /** Numeric `major.minor.patch…` comparison; a trailing `-<unix ts>` build id (injective) is ignored. */
        fun compareSemver(a: String, b: String): Int
        {
            val pa = semverParts(a)
            val pb = semverParts(b)
            for (i in 0 until maxOf(pa.size, pb.size))
            {
                val c = (pa.getOrElse(i) { 0L }).compareTo(pb.getOrElse(i) { 0L })
                if (c != 0) return c
            }
            return 0
        }

        private fun semverParts(v: String): List<Long> =
            v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
                .split('.').map { it.toLongOrNull() ?: 0L }
    }

    private fun JsonObject.isDraft(): Boolean = this["draft"]?.jsonPrimitive?.boolean ?: false

    private fun JsonObject.isPrerelease(): Boolean = this["prerelease"]?.jsonPrimitive?.boolean ?: false

    private fun JsonObject.toAsset(): GitHubReleaseAsset = GitHubReleaseAsset(
        name = this["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        browserDownloadUrl = this["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
