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

/** `GET /repos/{repo}/releases` — first non-draft, non-prerelease entry wins (releases are newest-first). */
class HttpGitHubReleaseClient(
    private val tokenProvider: GitHubTokenProvider,
    private val timeout: Duration = Duration.ofSeconds(10),
    private val apiBase: String = "https://api.github.com",
) : GitHubReleaseClient
{
    private val log = LoggerFactory.getLogger(HttpGitHubReleaseClient::class.java)

    override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease? = withContext(Dispatchers.IO) {
        try
        {
            val client = HttpClient.newBuilder().connectTimeout(timeout).build()
            val reqBuilder = HttpRequest.newBuilder(URI("$apiBase/repos/$repo/releases?per_page=20"))
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
                return@withContext null
            }
            val releases = Json.parseToJsonElement(resp.body()).jsonArray
            pickLatest(releases, tagPrefix)
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

    private fun pickLatest(releases: JsonArray, tagPrefix: String?): GitHubRelease?
    {
        for (raw in releases)
        {
            val release = raw.jsonObject
            if (release.isDraftOrPrerelease()) continue
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (tag.isEmpty()) continue
            if (tagPrefix != null && !tag.startsWith(tagPrefix)) continue
            return GitHubRelease(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                assets = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject.toAsset() },
                body = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        return null
    }

    private fun JsonObject.isDraftOrPrerelease(): Boolean =
        (this["draft"]?.jsonPrimitive?.boolean ?: false) || (this["prerelease"]?.jsonPrimitive?.boolean ?: false)

    private fun JsonObject.toAsset(): GitHubReleaseAsset = GitHubReleaseAsset(
        name = this["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        browserDownloadUrl = this["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}

private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
