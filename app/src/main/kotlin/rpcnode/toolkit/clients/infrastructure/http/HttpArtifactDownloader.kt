package rpcnode.toolkit.clients.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.clients.application.ArtifactDownloader
import rpcnode.toolkit.clients.application.GitHubTokenProvider

/** Streams to a `.part` sibling file, then atomically renames it onto [dest] — no partial files survive a crash. */
class HttpArtifactDownloader(
    private val tokenProvider: GitHubTokenProvider,
    private val connectTimeout: Duration = Duration.ofSeconds(10),
) : ArtifactDownloader
{
    override suspend fun download(url: String, dest: Path, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit)
    {
        withContext(Dispatchers.IO) {
            val parent = dest.parent
            if (parent != null)
            {
                Files.createDirectories(parent)
            }
            val part = dest.resolveSibling("${dest.fileName}.part")
            val client = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NORMAL).build()
            val reqBuilder = HttpRequest.newBuilder(URI(url))
                .header("User-Agent", "rpcnode-server")
                .header("Accept", "application/octet-stream")
            if (isGitHubHost(url))
            {
                val token = tokenProvider.current()
                if (!token.isNullOrBlank())
                {
                    reqBuilder.header("Authorization", "Bearer $token")
                }
            }
            val resp = client.send(reqBuilder.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() !in 200 until 300)
            {
                throw IllegalStateException("download $url: HTTP ${resp.statusCode()}")
            }
            val total = resp.headers().firstValueAsLong("Content-Length").orElse(-1)
            var read = 0L
            resp.body().use { input ->
                Files.newOutputStream(part).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true)
                    {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            Files.move(part, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun isGitHubHost(url: String): Boolean
    {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        return host == "github.com" || host == "api.github.com" || host.endsWith(".github.com") ||
            host == "raw.githubusercontent.com" || host == "objects.githubusercontent.com"
    }
}
