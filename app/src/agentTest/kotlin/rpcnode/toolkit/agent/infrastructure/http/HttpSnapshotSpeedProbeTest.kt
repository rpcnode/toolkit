package rpcnode.toolkit.agent.infrastructure.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpSnapshotSpeedProbeTest
{
    private var server: HttpServer? = null

    @AfterEach
    fun tearDown()
    {
        server?.stop(0)
        server = null
    }

    @Test
    fun range_sample_reports_throughput() = runTest {
        val payload = ByteArray(256 * 1024) { 7 }
        server = HttpServer.create(InetSocketAddress(0), 0).also { http ->
            http.createContext("/archive.tgz") { ex: HttpExchange ->
                val range = ex.requestHeaders.getFirst("Range")
                if (range != "bytes=0-1048575")
                {
                    ex.sendResponseHeaders(416, -1)
                    return@createContext
                }
                ex.responseHeaders.add("Content-Range", "bytes 0-${payload.size - 1}/${payload.size}")
                ex.sendResponseHeaders(206, payload.size.toLong())
                ex.responseBody.use { it.write(payload) }
            }
            http.start()
        }
        val port = server!!.address.port
        val probe = HttpSnapshotSpeedProbe(sampleBytes = payload.size.toLong())
        val reading = probe.probe("http://127.0.0.1:$port/archive.tgz")
        assertTrue(reading.available)
        assertEquals(payload.size.toLong(), reading.sampleBytes)
        assertNotNull(reading.bytesPerSec)
        assertTrue(reading.bytesPerSec!! > 0)
    }

    @Test
    fun http_error_is_unavailable() = runTest {
        server = HttpServer.create(InetSocketAddress(0), 0).also { http ->
            http.createContext("/missing.tgz") { ex: HttpExchange ->
                ex.sendResponseHeaders(404, -1)
            }
            http.start()
        }
        val port = server!!.address.port
        val reading = HttpSnapshotSpeedProbe().probe("http://127.0.0.1:$port/missing.tgz")
        assertFalse(reading.available)
        assertTrue(reading.detail.orEmpty().contains("404"))
    }
}
