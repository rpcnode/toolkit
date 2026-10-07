package rpcnode.toolkit.chains.xrpl.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Versions of `xrpld_<version>-1_amd64.deb` present in the Ripple apt pool. */
fun interface XrplDebPool
{
    /** Null when the pool index could not be read — callers then trust the GitHub tag. */
    suspend fun versions(): List<String>?
}

class HttpXrplDebPool(
    private val indexUrl: String = "https://repos.ripple.com/repos/rippled-deb/pool/stable/",
    private val timeout: Duration = Duration.ofSeconds(10),
) : XrplDebPool
{
    private val client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build()

    override suspend fun versions(): List<String>? = withContext(Dispatchers.IO) {
        try
        {
            val req = HttpRequest.newBuilder(URI(indexUrl)).timeout(timeout).GET().build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200 until 300)
            {
                return@withContext null
            }
            PACKAGE.findAll(resp.body()).map { it.groupValues[1] }.distinct().toList().ifEmpty { null }
        }
        catch (e: kotlinx.coroutines.CancellationException)
        {
            throw e
        }
        catch (_: Exception)
        {
            null
        }
    }

    private companion object
    {
        val PACKAGE = Regex("""xrpld_([0-9]+(?:\.[0-9]+)*)-1_amd64\.deb""")
    }
}
