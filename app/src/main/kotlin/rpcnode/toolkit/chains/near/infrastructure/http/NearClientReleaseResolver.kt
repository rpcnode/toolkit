package rpcnode.toolkit.chains.near.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmReleases
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * `near/nearcore` GitHub releases carry no assets: the binaries are on the official build bucket
 * (`…/nearcore/Linux-<arch>/<version>/neard`). Mainnet = highest final release, testnet = highest including `-rc.N`;
 * a release only counts once its x86_64 object exists on the bucket (the tag can be published before the upload).
 */
class NearClientReleaseResolver(
    private val github: GitHubReleaseClient,
    private val exists: suspend (url: String) -> Boolean = ::headOk,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        val includeRc = when (env.value)
        {
            "mainnet" -> false
            "testnet" -> true
            else -> return null
        }
        val candidates = NonEvmReleases.ranked(github.releases(REPO), includePrerelease = includeRc).take(MAX_CANDIDATES)
        for (r in candidates)
        {
            if (exists(binaryUrl(r.version))) return ClientRelease(version = r.version, tag = r.tag, sourceLabel = REPO)
        }
        return null
    }

    companion object
    {
        const val REPO = "near/nearcore"
        const val BUCKET = "https://s3-us-west-1.amazonaws.com/build.nearprotocol.com/nearcore"
        private const val MAX_CANDIDATES = 6

        fun binaryUrl(version: String, arch: String = "Linux-x86_64"): String = "$BUCKET/$arch/$version/neard"

        suspend fun headOk(url: String): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
                val req = HttpRequest.newBuilder(URI(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(15)).build()
                client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
            }.getOrDefault(false)
        }
    }
}
