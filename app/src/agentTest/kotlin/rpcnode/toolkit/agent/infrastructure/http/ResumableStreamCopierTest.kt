package rpcnode.toolkit.agent.infrastructure.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResumableStreamCopierTest
{
    private val payload = ByteArray(3 * 1024 * 1024).also { Random(7).nextBytes(it) }

    private fun serve(handler: (HttpExchange, Int) -> Unit): Pair<HttpServer, String>
    {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/snap.tgz") { ex -> handler(ex, calls.incrementAndGet()) }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
        return server to "http://127.0.0.1:${server.address.port}/snap.tgz"
    }

    /** Honors `Range: bytes=N-`; [cutAfter] bytes into the body the connection is dropped. */
    private fun HttpExchange.sendFrom(offset: Long, cutAfter: Int? = null)
    {
        val rest = payload.size - offset.toInt()
        if (offset > 0)
        {
            responseHeaders.add("Content-Range", "bytes $offset-${payload.size - 1}/${payload.size}")
            sendResponseHeaders(206, rest.toLong())
        }
        else
        {
            sendResponseHeaders(200, rest.toLong())
        }
        val body = responseBody
        if (cutAfter != null && cutAfter < rest)
        {
            body.write(payload, offset.toInt(), cutAfter)
            body.flush()
            close() // fewer bytes than Content-Length → the client sees a broken connection
            return
        }
        body.use { it.write(payload, offset.toInt(), rest) }
    }

    private fun HttpExchange.rangeStart(): Long =
        requestHeaders.getFirst("Range")?.substringAfter("bytes=")?.substringBefore("-")?.toLongOrNull() ?: 0L

    private fun copier(url: String, retryWindowMs: Long = 60_000) = ResumableStreamCopier(
        url = url,
        expectedBytes = payload.size.toLong(),
        idleTimeoutMs = 10_000,
        retryWindowMs = retryWindowMs,
        backoffMs = { 1L },
        sleep = { Thread.sleep(1) },
    )

    @Test
    fun resumes_after_connection_drops_and_delivers_every_byte_once()
    {
        val (server, url) = serve { ex, call ->
            // first two connections die mid-body, the third finishes
            ex.sendFrom(ex.rangeStart(), cutAfter = if (call <= 2) 700_000 else null)
        }
        try
        {
            val out = ByteArrayOutputStream()
            val retries = mutableListOf<Triple<Int, Long, String>>()
            copier(url).copy(out, onRetry = { a, already, why -> retries += Triple(a, already, why) })

            assertContentEquals(payload, out.toByteArray())
            assertEquals(2, retries.size, retries.toString())
            assertTrue(retries[1].second > retries[0].second, "second resume starts further in: $retries")
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun resume_sends_if_range_so_a_changed_file_is_never_glued_on()
    {
        val seen = java.util.concurrent.CopyOnWriteArrayList<String?>()
        val (server, url) = serve { ex, call ->
            if (call > 1) seen += ex.requestHeaders.getFirst("If-Range")
            ex.responseHeaders.add("ETag", "\"v1\"")
            ex.sendFrom(ex.rangeStart(), cutAfter = if (call == 1) 400_000 else null)
        }
        try
        {
            val out = ByteArrayOutputStream()
            copier(url).copy(out)
            assertContentEquals(payload, out.toByteArray())
            assertEquals(listOf<String?>("\"v1\""), seen.toList())
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun retries_server_errors_then_succeeds()
    {
        val (server, url) = serve { ex, call ->
            if (call <= 2)
            {
                ex.sendResponseHeaders(503, -1)
                ex.close()
            }
            else
            {
                ex.sendFrom(ex.rangeStart())
            }
        }
        try
        {
            val out = ByteArrayOutputStream()
            copier(url).copy(out)
            assertContentEquals(payload, out.toByteArray())
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun fails_instead_of_corrupting_when_the_mirror_ignores_range()
    {
        val (server, url) = serve { ex, call ->
            // always answers 200 with the whole file, even to a Range request
            ex.sendFrom(0, cutAfter = if (call == 1) 500_000 else null)
        }
        try
        {
            val out = ByteArrayOutputStream()
            val e = assertFailsWith<ResumableStreamCopier.Fatal> { copier(url).copy(out) }
            assertTrue(e.message!!.contains("ignores Range"), e.message)
            assertEquals(500_000, out.size(), "nothing was appended after the failed resume")
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun missing_file_fails_at_once()
    {
        val attempts = AtomicInteger()
        val (server, url) = serve { ex, _ ->
            attempts.incrementAndGet()
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        try
        {
            assertFailsWith<ResumableStreamCopier.Fatal> { copier(url).copy(ByteArrayOutputStream()) }
            assertEquals(1, attempts.get())
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun gives_up_after_the_retry_window_without_progress()
    {
        val (server, url) = serve { ex, _ ->
            ex.sendResponseHeaders(500, -1)
            ex.close()
        }
        try
        {
            val e = assertFailsWith<IOException> { copier(url, retryWindowMs = 150).copy(ByteArrayOutputStream()) }
            assertTrue(e.message!!.contains("network down"), e.message)
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun a_failing_sink_is_not_retried()
    {
        val attempts = AtomicInteger()
        val (server, url) = serve { ex, _ ->
            attempts.incrementAndGet()
            ex.sendFrom(0)
        }
        try
        {
            val broken = object : java.io.OutputStream()
            {
                override fun write(b: Int) = throw IOException("Broken pipe")
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Broken pipe")
            }
            assertFailsWith<ResumableStreamCopier.SinkFailed> { copier(url).copy(broken) }
            assertEquals(1, attempts.get())
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun abort_stops_the_copy()
    {
        val (server, url) = serve { ex, _ -> ex.sendFrom(0) }
        try
        {
            val e = assertFailsWith<IOException> {
                copier(url).copy(ByteArrayOutputStream(), isAborted = { true })
            }
            assertTrue(e.message!!.contains("aborted"))
        }
        finally
        {
            server.stop(0)
        }
    }
}
