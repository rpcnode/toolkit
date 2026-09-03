package rpcnode.toolkit.shared.infrastructure.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.time.Duration
import rpcnode.toolkit.shared.infrastructure.log.HttpIoLog

/** Shared CIO [HttpClient] factory for outbound probes (panel tip, host height, …). */
object SimpleHttpClients
{
    fun cio(timeout: Duration = Duration.ofSeconds(8)): HttpClient =
        HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                val ms = timeout.toMillis().coerceAtLeast(1L)
                requestTimeoutMillis = ms
                connectTimeoutMillis = ms
                socketTimeoutMillis = ms
            }
        }
}

/**
 * Thin suspend helpers over ktor-client. Returns body text on 2xx, otherwise null.
 * Does not throw for HTTP/network failures — probes treat null as “no reading”.
 */
class SimpleHttp(
    private val http: HttpClient = SimpleHttpClients.cio(),
)
{
    suspend fun getText(url: String, accept: String? = null): String? =
        request("GET", url) {
            http.get(url) {
                if (!accept.isNullOrBlank())
                {
                    header(HttpHeaders.Accept, accept)
                }
            }
        }

    /** POST with a raw JSON body (default empty object). */
    suspend fun postJson(url: String, body: String = "{}"): String? =
        request("POST", url) {
            http.post(url) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                setBody(body)
            }
        }

    private suspend fun request(
        method: String,
        url: String,
        block: suspend () -> io.ktor.client.statement.HttpResponse,
    ): String?
    {
        val started = System.nanoTime()
        return try
        {
            val resp = block()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            HttpIoLog.outbound(method, url, resp.status.value, elapsedMs)
            if (!resp.status.isSuccess())
            {
                return null
            }
            resp.bodyAsText()
        }
        catch (e: Exception)
        {
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            HttpIoLog.outbound(method, url, HttpStatusCode.BadGateway.value, elapsedMs, e.message)
            null
        }
    }
}
