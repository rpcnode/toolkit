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
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory

/**
 * Login-free JSON-RPC front for a bitcoind-style node (Bitcoin Core, Litecoin, Dogecoin, Dash, BCH).
 * Those daemons always demand `rpcuser`/`rpcpassword` or a cookie, so a plain client gets HTTP 401.
 * This proxy listens on its own port, adds the node's `Authorization: Basic …` itself and forwards the call to
 * 127.0.0.1:[targetPort].
 *
 * **Nothing is cached, nothing is rewritten.** Every request goes to the node and the node's answer comes back as
 * it is, so a client always sees the live state (a new block, a transaction that was just broadcast, a spent output).
 * The proxy only adds the login. **Every method passes** unless [allowedMethods] is set — the port is meant to sit
 * behind a firewall.
 *
 * **Load.** Each connection runs on a virtual thread, so tens of thousands of clients do not need a thread pool.
 * The node answers only [upstreamConcurrency]-ish calls at once (its `rpcthreads`); the proxy lets at most that many
 * run and queues the rest, instead of letting a burst hit the node's work queue and come back as `503`.
 * Capacity for uncached calls is therefore the node's own.
 */
class RpcAuthProxy(
    private val listenHost: String,
    val listenPort: Int,
    private val targetPort: Int,
    /** `user:password` for Basic auth, read per request (a Bitcoin Core cookie changes on every node restart). */
    private val credentials: () -> String?,
    /** null = every method passes; a set = only those ([READ_AND_BROADCAST] is a ready-made "safe" list). */
    private val allowedMethods: Set<String>? = null,
    private val maxBodyBytes: Int = 8 * 1024 * 1024,
    /** Calls allowed on the node at once; keep it at or below the node's `rpcthreads`. */
    private val upstreamConcurrency: Int = 64,
)
{
    private val log = LoggerFactory.getLogger(RpcAuthProxy::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val gate = Semaphore(upstreamConcurrency.coerceAtLeast(1), true)
    private var server: HttpServer? = null
    private var reporter: Thread? = null

    val requests = AtomicLong()
    val denied = AtomicLong()
    val failures = AtomicLong()

    /** Actual port (useful when [listenPort] is 0 in tests). */
    val boundPort: Int get() = server?.address?.port ?: listenPort

    fun start()
    {
        check(server == null) { "already started" }
        val s = HttpServer.create(InetSocketAddress(listenHost, listenPort), 4096)
        s.executor = Executors.newVirtualThreadPerTaskExecutor()
        s.createContext("/") { exchange -> handle(exchange) }
        s.start()
        server = s
        reporter = Thread.ofVirtual().name("rpc-proxy-report-$listenPort").start {
            var last = 0L
            while (!Thread.currentThread().isInterrupted)
            {
                try
                {
                    TimeUnit.SECONDS.sleep(60)
                }
                catch (_: InterruptedException)
                {
                    break
                }
                val total = requests.get()
                if (total != last)
                {
                    log.info("rpc proxy :{} {}", boundPort, stats())
                    last = total
                }
            }
        }
        log.info(
            "rpc proxy {}:{} -> 127.0.0.1:{} (up to {} calls on the node at once, {})",
            listenHost, boundPort, targetPort, upstreamConcurrency,
            if (allowedMethods == null) "all methods" else "${allowedMethods.size} methods",
        )
    }

    fun stop()
    {
        reporter?.interrupt()
        reporter = null
        server?.stop(0)
        server = null
        log.info("rpc proxy :{} stopped", listenPort)
    }

    fun stats(): String = "requests=${requests.get()} denied=${denied.get()} failures=${failures.get()}"

    private fun handle(ex: HttpExchange)
    {
        requests.incrementAndGet()
        try
        {
            if (ex.requestMethod != "POST")
            {
                reply(ex, 405, error(-32600, "use POST with a JSON-RPC body"))
                return
            }
            val body = ex.requestBody.readNBytes(maxBodyBytes + 1)
            if (body.size > maxBodyBytes)
            {
                reply(ex, 413, error(-32600, "request too large"))
                return
            }
            val problem = firstProblem(runCatching { json.parseToJsonElement(String(body, Charsets.UTF_8)) }.getOrNull())
            if (problem != null)
            {
                denied.incrementAndGet()
                if (problem.malformed)
                {
                    reply(ex, 400, error(-32600, "invalid JSON-RPC request: ${problem.what}"))
                }
                else
                {
                    reply(ex, 403, error(-32601, "method not allowed by the RpcNode proxy: ${problem.what}"))
                }
                return
            }
            val creds = credentials()
            if (creds.isNullOrBlank())
            {
                reply(ex, 503, error(-32603, "node RPC credentials are not available yet (is the node started?)"))
                return
            }
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$targetPort${ex.requestURI.rawPath.ifBlank { "/" }}"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(creds.toByteArray()))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            gate.acquire()
            val resp = try
            {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            }
            finally
            {
                gate.release()
            }
            ex.responseHeaders.add("Content-Type", resp.headers().firstValue("Content-Type").orElse("application/json"))
            val out = resp.body()
            if (out.isEmpty())
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
            runCatching { reply(ex, 502, error(-32603, "the node RPC is not reachable on 127.0.0.1:$targetPort (starting or stopped)")) }
        }
        catch (e: Exception)
        {
            failures.incrementAndGet()
            log.warn("rpc proxy :{} {}", listenPort, e.message)
            runCatching { reply(ex, 502, error(-32603, "proxy error: ${e.message ?: e.javaClass.simpleName}")) }
        }
    }

    private class Problem(val what: String, val malformed: Boolean)

    /** A request that cannot be forwarded: not JSON-RPC at all, or (in safe mode) a method outside the allow list. */
    private fun firstProblem(root: JsonElement?): Problem?
    {
        val calls: List<JsonObject> = when (root)
        {
            null -> return Problem("not valid JSON", true)
            is JsonObject -> listOf(root)
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            else -> return Problem("expected an object or an array", true)
        }
        if (calls.isEmpty())
        {
            return Problem("empty request", true)
        }
        for (call in calls)
        {
            val method = (call["method"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (method.isEmpty())
            {
                return Problem("missing method", true)
            }
            if (allowedMethods != null && method !in allowedMethods)
            {
                return Problem(method, false)
            }
        }
        return null
    }

    private fun error(code: Int, message: String): String =
        """{"result":null,"error":{"code":$code,"message":${JsonPrimitive(message)}},"id":null}"""

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
            // The JDK server closes idle keep-alive connections beyond 200 (and the client then sees a reset on its
            // next call). A public RPC port has far more clients than that. Must be set before the first HttpServer.
            System.setProperty("sun.net.httpserver.maxIdleConnections", "1000000")
            System.setProperty("sun.net.httpserver.nodelay", "true")
        }

        /**
         * Optional "safe" list for a port that is NOT behind a firewall: chain reads and broadcasting only. No wallet,
         * no node control, no peer/ban management, no `submitblock`/`invalidateblock`.
         */
        val READ_AND_BROADCAST: Set<String> = setOf(
            "getblockcount", "getbestblockhash", "getblockhash", "getblock", "getblockheader", "getblockchaininfo",
            "getblockstats", "getblockfilter", "getchaintips", "getchaintxstats", "getdifficulty",
            "getrawtransaction", "decoderawtransaction", "decodescript", "gettransaction", "gettxout", "gettxoutproof",
            "verifytxoutproof", "getmempoolinfo", "getrawmempool", "getmempoolentry", "getmempoolancestors",
            "getmempooldescendants", "testmempoolaccept", "sendrawtransaction", "estimatesmartfee", "estimaterawfee",
            "getnetworkinfo", "getnettotals", "getconnectioncount", "getmininginfo", "getnetworkhashps",
            "validateaddress", "getaddressinfo", "uptime", "help", "getindexinfo", "scantxoutset",
            "getinfo", "getblockhashes", "getspentinfo", "getaddressbalance", "getaddresstxids", "getaddressdeltas",
            "getaddressutxos", "getaddressmempool", "getmasternodecount", "getgovernanceinfo",
        )
    }
}
