package rpcnode.toolkit.agent.infrastructure.proxy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

/**
 * One public URL for a TON node, like a hosted TON provider: the **TON HTTP API v2** (`ton-http-api`, root paths such
 * as `/getMasterchainInfo`, `/jsonRPC`) and the **v3 indexer** (`/api/v3/...`) behind the same host and port.
 *
 *  - `/api/v3/...`              → the indexer, path unchanged
 *  - `/api/v2/<method>`         → ton-http-api `/<method>`  (it has no `/api/v2` prefix of its own)
 *  - `POST /`                   → ton-http-api `/jsonRPC`   (JSON-RPC at the base URL)
 *  - anything else (`/getMasterchainInfo`, `/jsonRPC`, `/docs` …) → ton-http-api as it is
 *
 * Nothing is cached and nothing is rewritten in the body: the answer of the backend comes back as it is.
 */
object TonApiRoutes
{
    enum class Backend { V2, V3 }

    data class Route(val backend: Backend, val path: String)

    fun resolve(method: String, rawPath: String): Route
    {
        val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        return when
        {
            path == "/api/v3" || path.startsWith("/api/v3/") -> Route(Backend.V3, path)
            path == "/api/v2" || path == "/api/v2/" -> Route(Backend.V2, "/")
            path.startsWith("/api/v2/") -> Route(Backend.V2, path.removePrefix("/api/v2"))
            path == "/" && method.equals("POST", ignoreCase = true) -> Route(Backend.V2, "/jsonRPC")
            else -> Route(Backend.V2, path)
        }
    }
}

class TonApiRouter(
    private val listenHost: String,
    val listenPort: Int,
    private val v2Port: Int,
    /** Loopback port of the v3 indexer API; 0 = none installed. */
    private val v3Port: Int,
    private val maxBodyBytes: Int = 8 * 1024 * 1024,
    private val upstreamConcurrency: Int = 256,
)
{
    private val log = LoggerFactory.getLogger(TonApiRouter::class.java)
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val gate = Semaphore(upstreamConcurrency.coerceAtLeast(1), true)
    private var server: HttpServer? = null

    val requests = AtomicLong()
    val failures = AtomicLong()

    val boundPort: Int get() = server?.address?.port ?: listenPort

    fun start()
    {
        check(server == null) { "already started" }
        val s = HttpServer.create(InetSocketAddress(listenHost, listenPort), 4096)
        s.executor = Executors.newVirtualThreadPerTaskExecutor()
        s.createContext("/") { exchange -> handle(exchange) }
        s.start()
        server = s
        log.info("ton router {}:{} -> v2 127.0.0.1:{}, v3 127.0.0.1:{}", listenHost, boundPort, v2Port, v3Port)
    }

    fun stop()
    {
        server?.stop(0)
        server = null
        log.info("ton router :{} stopped", listenPort)
    }

    private fun handle(ex: HttpExchange)
    {
        requests.incrementAndGet()
        val uri = ex.requestURI
        val route = TonApiRoutes.resolve(ex.requestMethod, uri.rawPath.orEmpty())
        val port = if (route.backend == TonApiRoutes.Backend.V3) v3Port else v2Port
        try
        {
            if (port <= 0)
            {
                reply(ex, 503, error(503, "the TON v3 indexer is not installed on this host"))
                return
            }
            val body = ex.requestBody.readNBytes(maxBodyBytes + 1)
            if (body.size > maxBodyBytes)
            {
                reply(ex, 413, error(413, "request too large"))
                return
            }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val publisher = if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body)
            val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port${route.path}$query"))
                .timeout(Duration.ofMinutes(5))
                .method(ex.requestMethod, publisher)
            for (name in FORWARDED_REQUEST_HEADERS)
            {
                ex.requestHeaders.getFirst(name)?.let { builder.header(name, it) }
            }
            gate.acquire()
            val resp = try
            {
                client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            }
            finally
            {
                gate.release()
            }
            resp.headers().map().forEach { (name, values) ->
                if (name.lowercase(Locale.ROOT) !in HOP_BY_HOP)
                {
                    values.forEach { ex.responseHeaders.add(name, it) }
                }
            }
            val out = resp.body()
            if (out.isEmpty() || ex.requestMethod == "HEAD")
            {
                ex.sendResponseHeaders(resp.statusCode(), -1)
                ex.close()
                return
            }
            ex.sendResponseHeaders(resp.statusCode(), out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        catch (e: ConnectException)
        {
            failures.incrementAndGet()
            val what = if (route.backend == TonApiRoutes.Backend.V3) "TON v3 indexer" else "TON HTTP API"
            runCatching { reply(ex, 502, error(502, "$what is not reachable on 127.0.0.1:$port (starting or stopped)")) }
        }
        catch (e: Exception)
        {
            failures.incrementAndGet()
            log.warn("ton router :{} {}", listenPort, e.message)
            runCatching { reply(ex, 502, error(502, "router error: ${e.message ?: e.javaClass.simpleName}")) }
        }
    }

    private fun error(code: Int, message: String): String =
        """{"ok":false,"error":${JsonPrimitive(message)},"code":$code}"""

    private fun reply(ex: HttpExchange, status: Int, body: String)
    {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object
    {
        init
        {
            System.setProperty("sun.net.httpserver.maxIdleConnections", "1000000")
            System.setProperty("sun.net.httpserver.nodelay", "true")
        }

        private val FORWARDED_REQUEST_HEADERS = listOf("Content-Type", "Accept", "Authorization", "X-API-Key", "Origin")

        // content-length / transfer-encoding are recomputed by the JDK server for the buffered body.
        private val HOP_BY_HOP = setOf("connection", "keep-alive", "transfer-encoding", "content-length", "upgrade", "te", "trailer")
    }
}
