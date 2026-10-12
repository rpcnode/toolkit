package rpcnode.toolkit.agent.infrastructure.proxy

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.agent.infrastructure.proxy.TonApiRoutes.Backend

class TonApiRouterTest
{
    @Test
    fun v3_keeps_its_path_and_v2_loses_the_api_prefix()
    {
        assertEquals(TonApiRoutes.Route(Backend.V3, "/api/v3/transactions"), TonApiRoutes.resolve("GET", "/api/v3/transactions"))
        assertEquals(TonApiRoutes.Route(Backend.V3, "/api/v3"), TonApiRoutes.resolve("GET", "/api/v3"))
        assertEquals(TonApiRoutes.Route(Backend.V2, "/getMasterchainInfo"), TonApiRoutes.resolve("GET", "/api/v2/getMasterchainInfo"))
        assertEquals(TonApiRoutes.Route(Backend.V2, "/jsonRPC"), TonApiRoutes.resolve("POST", "/api/v2/jsonRPC"))
        assertEquals(TonApiRoutes.Route(Backend.V2, "/"), TonApiRoutes.resolve("GET", "/api/v2"))
    }

    @Test
    fun bare_paths_go_to_v2_and_a_post_to_the_root_is_json_rpc()
    {
        assertEquals(TonApiRoutes.Route(Backend.V2, "/getMasterchainInfo"), TonApiRoutes.resolve("GET", "/getMasterchainInfo"))
        assertEquals(TonApiRoutes.Route(Backend.V2, "/jsonRPC"), TonApiRoutes.resolve("POST", "/"))
        assertEquals(TonApiRoutes.Route(Backend.V2, "/"), TonApiRoutes.resolve("GET", "/"))
        // a v3-looking name that is not the prefix stays on v2
        assertEquals(TonApiRoutes.Route(Backend.V2, "/api/v30/x"), TonApiRoutes.resolve("GET", "/api/v30/x"))
    }

    private class Upstream(val name: String)
    {
        val seen = mutableListOf<String>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
                seen += "${ex.requestMethod} ${ex.requestURI} body=$body ct=${ex.requestHeaders.getFirst("Content-Type")}"
                val out = """{"from":"$name"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
                ex.sendResponseHeaders(if (ex.requestURI.path == "/missing") 404 else 200, out.size.toLong())
                ex.responseBody.use { it.write(out) }
            }
            start()
        }
        val port: Int get() = server.address.port
        fun stop() = server.stop(0)
    }

    private val client = HttpClient.newHttpClient()
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanup() = closers.forEach { runCatching { it() } }

    private fun router(v2: Int, v3: Int): TonApiRouter
    {
        val r = TonApiRouter("127.0.0.1", 0, v2, v3)
        r.start()
        closers += { r.stop() }
        return r
    }

    private fun call(router: TonApiRouter, method: String, path: String, body: String? = null): HttpResponse<String>
    {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:${router.boundPort}$path"))
        if (body != null)
        {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body))
        }
        else
        {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun forwards_each_prefix_to_its_backend_with_query_body_and_headers()
    {
        val v2 = Upstream("v2").also { closers += it::stop }
        val v3 = Upstream("v3").also { closers += it::stop }
        val router = router(v2.port, v3.port)

        val a = call(router, "GET", "/api/v3/transactions?limit=3&offset=1")
        assertEquals(200, a.statusCode())
        assertEquals("""{"from":"v3"}""", a.body())
        assertEquals("*", a.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
        assertEquals(listOf("GET /api/v3/transactions?limit=3&offset=1 body= ct=null"), v3.seen)

        val b = call(router, "GET", "/api/v2/getMasterchainInfo")
        assertEquals("""{"from":"v2"}""", b.body())
        val c = call(router, "POST", "/", """{"jsonrpc":"2.0","method":"getMasterchainInfo","id":"1","params":{}}""")
        assertEquals("""{"from":"v2"}""", c.body())
        assertEquals(
            listOf(
                "GET /getMasterchainInfo body= ct=null",
                """POST /jsonRPC body={"jsonrpc":"2.0","method":"getMasterchainInfo","id":"1","params":{}} ct=application/json""",
            ),
            v2.seen,
        )
        assertEquals(404, call(router, "GET", "/missing").statusCode(), "backend status codes pass through")
    }

    @Test
    fun a_missing_indexer_is_503_and_a_stopped_backend_is_502_with_a_json_body()
    {
        val v2 = Upstream("v2").also { closers += it::stop }
        val noIndexer = router(v2.port, 0)
        val r1 = call(noIndexer, "GET", "/api/v3/blocks")
        assertEquals(503, r1.statusCode())
        assertTrue(r1.body().contains("indexer is not installed"), r1.body())

        val dead = ServerSocket(0).use { it.localPort }
        val down = router(dead, dead)
        val r2 = call(down, "GET", "/getMasterchainInfo")
        assertEquals(502, r2.statusCode())
        assertTrue(r2.body().contains("TON HTTP API is not reachable"), r2.body())
        assertEquals(502, call(down, "GET", "/api/v3/blocks").statusCode())
    }
}
