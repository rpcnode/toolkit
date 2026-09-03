package rpcnode.toolkit.settings.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.settings.application.save.GitHubTokenCheck
import rpcnode.toolkit.settings.application.save.GitHubTokenChecker

class HttpGitHubTokenChecker(
    private val timeout: Duration = Duration.ofSeconds(8),
    private val rateLimitUrl: URI = URI("https://api.github.com/rate_limit"),
) : GitHubTokenChecker
{
    override suspend fun check(token: String): GitHubTokenCheck = withContext(Dispatchers.IO) {
        try
        {
            val client = HttpClient.newBuilder().connectTimeout(timeout).build()
            val req = HttpRequest.newBuilder(rateLimitUrl)
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "rpcnode-server")
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
            when (resp.statusCode())
            {
                401, 403 -> GitHubTokenCheck.Rejected
                in 200 until 300 -> GitHubTokenCheck.Ok
                else -> GitHubTokenCheck.Failed("github rate_limit: ${resp.statusCode()}")
            }
        }
        catch (e: Exception)
        {
            if (e is kotlinx.coroutines.CancellationException) throw e
            GitHubTokenCheck.Failed(e.message ?: "github unreachable")
        }
    }
}
