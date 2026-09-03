package rpcnode.toolkit.cdn.infrastructure.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResumableHttpDownloadTest
{
    @Test
    fun keeps_tmp_and_resumes_after_drop()
    {
        val payload = ByteArray(80_000) { it.toByte() }
        var first = true
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/file.bin") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            if (first)
            {
                first = false
                ex.responseHeaders.add("Content-Length", payload.size.toString())
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.write(payload, 0, 30_000)
                ex.responseBody.close()
                return@createContext
            }
            val start = range
                ?.removePrefix("bytes=")
                ?.substringBefore("-")
                ?.toLongOrNull()
                ?: 0L
            val rest = payload.size - start.toInt()
            ex.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
            ex.sendResponseHeaders(206, rest.toLong())
            ex.responseBody.write(payload, start.toInt(), rest)
            ex.responseBody.close()
        }
        server.start()
        try
        {
            val dir = Files.createTempDirectory("cdn-resume")
            val dest = dir.resolve("file.bin.tmp")
            val url = "http://127.0.0.1:${server.address.port}/file.bin"
            val download = ResumableHttpDownload(connectTimeoutMs = 2_000, readTimeoutMs = 5_000)
            try
            {
                download.fetch("tron/mainnet file.bin", url, dest, payload.size.toLong())
            }
            catch (_: Exception)
            {
                // first pass is cut short on purpose
            }
            assertEquals(30_000L, Files.size(dest))
            download.fetch("tron/mainnet file.bin", url, dest, payload.size.toLong())
            assertEquals(payload.size.toLong(), Files.size(dest))
            assertTrue(payload.contentEquals(Files.readAllBytes(dest)))
        }
        finally
        {
            server.stop(0)
        }
    }
}
