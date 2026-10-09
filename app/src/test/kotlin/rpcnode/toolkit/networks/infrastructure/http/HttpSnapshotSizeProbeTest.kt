package rpcnode.toolkit.networks.infrastructure.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class HttpSnapshotSizeProbeTest
{
    @Test
    fun head_content_length_is_the_size() = runTest {
        val server = startServer { exchange ->
            exchange.responseHeaders.add("Content-Length", "4096")
            exchange.sendResponseHeaders(200, -1)
            exchange.responseBody.close()
        }
        try
        {
            val probe = HttpSnapshotSizeProbe()
            assertEquals(4096, probe.bytes("http://127.0.0.1:${server.address.port}/archive.tgz"))
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun range_get_content_range_is_used_when_head_has_no_length() = runTest {
        val server = startServer { exchange ->
            if (exchange.requestMethod == "HEAD")
            {
                exchange.sendResponseHeaders(405, -1)
                exchange.responseBody.close()
                return@startServer
            }
            exchange.responseHeaders.add("Content-Range", "bytes 0-0/8192")
            exchange.sendResponseHeaders(206, 1)
            exchange.responseBody.use { it.write(byteArrayOf(0)) }
        }
        try
        {
            val probe = HttpSnapshotSizeProbe()
            assertEquals(8192, probe.bytes("http://127.0.0.1:${server.address.port}/archive.tgz"))
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun a_fresh_probe_is_cached_until_the_ttl_elapses() = runTest {
        val hits = AtomicInteger(0)
        val server = startServer { exchange ->
            hits.incrementAndGet()
            exchange.responseHeaders.add("Content-Length", "16")
            exchange.sendResponseHeaders(200, -1)
            exchange.responseBody.close()
        }
        try
        {
            val probe = HttpSnapshotSizeProbe(cacheTtl = Duration.ofMinutes(30))
            val url = "http://127.0.0.1:${server.address.port}/archive.tgz"
            assertEquals(16, probe.bytes(url))
            assertEquals(16, probe.bytes(url))
            assertEquals(1, hits.get())
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun a_blank_url_is_null() = runTest {
        assertNull(HttpSnapshotSizeProbe().bytes("   "))
    }

    @Test
    fun content_range_total_is_parsed()
    {
        assertEquals(8192, HttpSnapshotSizeProbe.parseContentRangeTotal("bytes 0-0/8192"))
        assertNull(HttpSnapshotSizeProbe.parseContentRangeTotal("bytes 0-0/*"))
        assertNull(HttpSnapshotSizeProbe.parseContentRangeTotal(null))
    }

    private fun startServer(handle: (HttpExchange) -> Unit): HttpServer
    {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        return server
    }
}
