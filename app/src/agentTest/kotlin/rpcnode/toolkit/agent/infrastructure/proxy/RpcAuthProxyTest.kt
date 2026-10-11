package rpcnode.toolkit.agent.infrastructure.proxy

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RpcAuthProxyTest
{
    companion object
    {
        init
        {
            // before the fake node's HttpServer exists (same JVM-wide setting the proxy applies in production)
            System.setProperty("sun.net.httpserver.maxIdleConnections", "1000000")
            System.setProperty("sun.net.httpserver.nodelay", "true")
        }
    }

    /** Stands in for bitcoind: 401 unless the right Basic credentials come with the call; counts what reaches it. */
    private val lastBody = AtomicReference<String?>(null)
    private val nodeCalls = AtomicInteger()
    private val inNode = AtomicInteger()
    private val maxInNode = AtomicInteger()
    @Volatile private var nodeDelayMs = 0L
    @Volatile private var blockCount = 100

    private val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newVirtualThreadPerTaskExecutor()
        createContext("/") { ex ->
            val want = "Basic " + Base64.getEncoder().encodeToString("user:secret".toByteArray())
            val got = ex.requestHeaders.getFirst("Authorization")
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            lastBody.set(body)
            nodeCalls.incrementAndGet()
            maxInNode.accumulateAndGet(inNode.incrementAndGet()) { a, b -> maxOf(a, b) }
            if (nodeDelayMs > 0) Thread.sleep(nodeDelayMs)
            inNode.decrementAndGet()
            val id = Regex("""\"id\"\s*:\s*(\"[^\"]*\"|[0-9]+|null)""").find(body)?.groupValues?.get(1) ?: "null"
            val (status, payload) =
                if (got != want) 401 to ""
                else 200 to """{"result":$blockCount,"error":null,"id":$id}"""
            val bytes = payload.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        start()
    }

    private var creds: String? = "user:secret"
    private fun newProxy(allowed: Set<String>? = null, concurrency: Int = 4) = RpcAuthProxy(
        listenHost = "127.0.0.1",
        listenPort = 0,
        targetPort = upstream.address.port,
        credentials = { creds },
        allowedMethods = allowed,
        upstreamConcurrency = concurrency,
    ).also { it.start() }

    private val proxy = newProxy()
    private val extra = mutableListOf<RpcAuthProxy>()
    private val http = HttpClient.newHttpClient()

    @AfterTest
    fun stop()
    {
        proxy.stop()
        extra.forEach { it.stop() }
        upstream.stop(0)
    }

    private fun post(body: String, via: RpcAuthProxy = proxy): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${via.boundPort}/"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun a_client_without_any_login_gets_through_because_the_proxy_adds_it()
    {
        val res = post("""{"jsonrpc":"1.0","id":1,"method":"getblockcount","params":[]}""")
        assertEquals(200, res.statusCode())
        assertTrue(res.body().contains("\"result\":100"), res.body())
    }

    @Test
    fun the_node_itself_still_says_401_without_the_proxy()
    {
        val direct = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${upstream.address.port}/"))
                .POST(HttpRequest.BodyPublishers.ofString("""{"method":"getblockcount"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(401, direct.statusCode())
    }

    @Test
    fun nothing_is_cached_every_call_reaches_the_node_and_the_answer_is_live()
    {
        assertTrue(post("""{"id":1,"method":"getblockcount","params":[]}""").body().contains("\"result\":100"))
        blockCount = 101 // a new block right after the first call
        val second = post("""{"id":2,"method":"getblockcount","params":[]}""")
        assertTrue(second.body().contains("\"result\":101"), "stale answer: ${second.body()}")
        repeat(8) { post("""{"id":3,"method":"getblockcount","params":[]}""") }
        assertEquals(10, nodeCalls.get(), "every request must go to the node")
    }

    @Test
    fun the_answer_is_the_nodes_own_untouched()
    {
        val res = post("""{"jsonrpc":"1.0","id":"abc","method":"getblockcount","params":[]}""")
        assertEquals("""{"result":100,"error":null,"id":"abc"}""", res.body())
    }

    @Test
    fun broadcasting_and_state_changing_calls_always_reach_the_node()
    {
        repeat(3) { post("""{"id":1,"method":"sendrawtransaction","params":["00"]}""") }
        repeat(2) { post("""{"id":1,"method":"testmempoolaccept","params":[["00"]]}""") }
        assertEquals(5, nodeCalls.get())
    }

    @Test
    fun every_method_passes_by_default_the_firewall_is_the_guard()
    {
        for (method in listOf("stop", "setban", "addnode", "walletpassphrase", "dumpprivkey", "invalidateblock", "submitblock", "getnewaddress"))
        {
            lastBody.set(null)
            val res = post("""{"jsonrpc":"1.0","id":1,"method":"$method","params":[]}""")
            assertEquals(200, res.statusCode(), method)
            assertTrue(lastBody.get()!!.contains("\"$method\""), "$method was not forwarded")
        }
    }

    @Test
    fun safe_mode_blocks_dangerous_methods_before_the_node()
    {
        val safe = newProxy(allowed = RpcAuthProxy.READ_AND_BROADCAST).also { extra += it }
        for (method in listOf("stop", "setban", "addnode", "walletpassphrase", "dumpprivkey", "invalidateblock", "submitblock"))
        {
            lastBody.set(null)
            val res = post("""{"jsonrpc":"1.0","id":1,"method":"$method","params":[]}""", safe)
            assertEquals(403, res.statusCode(), method)
            assertNull(lastBody.get(), "$method was forwarded")
        }
        assertEquals(200, post("""{"id":1,"method":"getblockcount","params":[]}""", safe).statusCode())
        // one forbidden call inside a batch blocks the whole batch
        lastBody.set(null)
        assertEquals(403, post("""[{"method":"getblockcount","id":1},{"method":"stop","id":2}]""", safe).statusCode())
        assertNull(lastBody.get())
    }

    @Test
    fun a_batch_goes_to_the_node_as_one_request()
    {
        val res = post("""[{"method":"getblockcount","id":1},{"method":"getbestblockhash","id":2}]""")
        assertEquals(200, res.statusCode())
        assertEquals(1, nodeCalls.get())
        assertEquals("""[{"method":"getblockcount","id":1},{"method":"getbestblockhash","id":2}]""", lastBody.get())
    }

    @Test
    fun malformed_requests_are_refused_with_400()
    {
        assertEquals(400, post("not json").statusCode())
        assertEquals(400, post("[]").statusCode())
        assertEquals(400, post("""{"id":1}""").statusCode())
        assertEquals(0, nodeCalls.get())
    }

    @Test
    fun only_post_is_accepted()
    {
        val res = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:${proxy.boundPort}/")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(405, res.statusCode())
    }

    @Test
    fun missing_credentials_are_reported_not_forwarded()
    {
        creds = null
        val res = post("""{"method":"getblockcount","id":1}""")
        assertEquals(503, res.statusCode())
        assertEquals(0, nodeCalls.get())
    }

    @Test
    fun a_cookie_that_changes_after_a_node_restart_is_picked_up_per_request()
    {
        creds = "__cookie__:old"
        assertEquals(401, post("""{"method":"getblockcount","id":1}""").statusCode())
        creds = "user:secret"
        assertEquals(200, post("""{"method":"getblockcount","id":1}""").statusCode())
    }

    @Test
    fun the_node_never_sees_more_calls_at_once_than_the_limit()
    {
        nodeDelayMs = 40
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val futures = (1..200).map { i ->
            pool.submit<Int> { post("""{"id":$i,"method":"getblockhash","params":[$i]}""").statusCode() }
        }
        assertTrue(futures.all { it.get() == 200 })
        pool.shutdown()
        assertEquals(200, nodeCalls.get())
        assertTrue(maxInNode.get() <= 4, "node saw ${maxInNode.get()} calls at once, limit is 4")
    }

    @Test
    fun many_clients_at_once_all_get_a_live_answer()
    {
        val wide = newProxy(concurrency = 64).also { extra += it }
        val total = 30_000
        val clients = 256
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        val started = System.nanoTime()
        val futures = (1..clients).map { c ->
            pool.submit<Int> {
                var okCount = 0
                for (i in 1..total / clients)
                {
                    if (post("""{"id":$c,"method":"getblockcount","params":[]}""", wide).statusCode() == 200) okCount++
                }
                okCount
            }
        }
        val okTotal = futures.sumOf { it.get() }
        val seconds = (System.nanoTime() - started) / 1e9
        pool.shutdown()
        val rps = okTotal / seconds
        println("RPC PROXY THROUGHPUT (no cache): %d requests, %d clients, %.0f req/s, node calls=%d".format(okTotal, clients, rps, nodeCalls.get()))
        assertEquals(total / clients * clients, okTotal)
        assertEquals(okTotal, nodeCalls.get(), "no request may be answered without asking the node")
        assertTrue(rps > 2_000, "too slow: %.0f req/s".format(rps))
    }

    @Test
    fun a_stopped_node_gives_a_clear_502()
    {
        upstream.stop(0)
        val res = post("""{"method":"getblockcount","id":1}""")
        assertEquals(502, res.statusCode())
        assertTrue(res.body().contains("not reachable"), res.body())
    }
}
