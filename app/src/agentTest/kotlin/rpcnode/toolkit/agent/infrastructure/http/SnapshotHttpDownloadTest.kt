package rpcnode.toolkit.agent.infrastructure.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnapshotHttpDownloadTest
{
    private var server: HttpServer? = null

    @AfterTest
    fun tearDown()
    {
        server?.stop(0)
        server = null
    }

    @Test
    fun resumes_after_partial_then_completes()
    {
        val payload = ByteArray(64 * 1024) { (it % 251).toByte() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { http ->
            http.createContext("/snap.tgz") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                if (range == null)
                {
                    // Advertise full size but close after half — forces resume.
                    ex.sendResponseHeaders(200, payload.size.toLong())
                    ex.responseBody.use { out ->
                        out.write(payload, 0, payload.size / 2)
                    }
                }
                else
                {
                    val start = range.removePrefix("bytes=").substringBefore('-').toLong()
                    val rest = payload.copyOfRange(start.toInt(), payload.size)
                    ex.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                    ex.sendResponseHeaders(206, rest.size.toLong())
                    ex.responseBody.use { it.write(rest) }
                }
                ex.close()
            }
            http.start()
        }
        val port = server!!.address.port
        val dest = Files.createTempFile("snap-dl", ".tgz")
        Files.deleteIfExists(dest)
        val retries = mutableListOf<String>()
        SnapshotHttpDownload(readTimeoutMs = 5_000, maxAttempts = 5, preferCurl = false).fetch(
            label = "test",
            url = "http://127.0.0.1:$port/snap.tgz",
            dest = dest,
            expectedBytes = payload.size.toLong(),
            onRetry = { attempt, already, reason -> retries += "$attempt:$already:$reason" },
        ) { _, _ -> }

        assertEquals(payload.size.toLong(), Files.size(dest))
        assertTrue(retries.isNotEmpty(), "expected at least one resume retry")
        assertTrue(Files.readAllBytes(dest).contentEquals(payload))
        Files.deleteIfExists(dest)
    }

    @Test
    fun curl_continues_partial_file_when_available()
    {
        if (!SnapshotHttpDownload.curlAvailable())
        {
            return
        }
        val payload = ByteArray(48_000) { (it % 199).toByte() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { http ->
            http.createContext("/curl.tgz") { ex ->
                val range = ex.requestHeaders.getFirst("Range")
                if (range == null)
                {
                    ex.sendResponseHeaders(200, payload.size.toLong())
                    ex.responseBody.use { out -> out.write(payload, 0, 12_000) }
                }
                else
                {
                    val start = range.removePrefix("bytes=").substringBefore('-').toLong()
                    val rest = payload.copyOfRange(start.toInt(), payload.size)
                    ex.responseHeaders.add("Content-Range", "bytes $start-${payload.size - 1}/${payload.size}")
                    ex.sendResponseHeaders(206, rest.size.toLong())
                    ex.responseBody.use { it.write(rest) }
                }
                ex.close()
            }
            http.start()
        }
        val port = server!!.address.port
        val dest = Files.createTempFile("snap-curl", ".tgz")
        Files.deleteIfExists(dest)
        SnapshotHttpDownload(maxAttempts = 8, preferCurl = true).fetch(
            label = "curl-test",
            url = "http://127.0.0.1:$port/curl.tgz",
            dest = dest,
            expectedBytes = payload.size.toLong(),
        ) { _, _ -> }

        assertEquals(payload.size.toLong(), Files.size(dest))
        assertTrue(Files.readAllBytes(dest).contentEquals(payload))
        Files.deleteIfExists(dest)
    }

    @Test
    fun aria2_and_curl_get_the_proxy_as_a_flag_because_detached_units_do_not_inherit_the_agent_env()
    {
        val dest = java.nio.file.Path.of("/data/x/.toolkit/a.tgz")
        val dl = SnapshotHttpDownload()
        val aria = dl.downloadCommand(rpcnode.toolkit.agent.infrastructure.proc.EnsureHostDownloader.Tool.Aria2, "https://m/a.tgz", dest, "http://u:p@h:3128")
        assertEquals(listOf("--all-proxy=http://u:p@h:3128", "https://m/a.tgz"), aria.takeLast(2))
        val curl = dl.downloadCommand(rpcnode.toolkit.agent.infrastructure.proc.EnsureHostDownloader.Tool.Curl, "https://m/a.tgz", dest, "http://u:p@h:3128")
        assertEquals(listOf("-x", "http://u:p@h:3128", "https://m/a.tgz"), curl.takeLast(3))
        val direct = dl.downloadCommand(rpcnode.toolkit.agent.infrastructure.proc.EnsureHostDownloader.Tool.Curl, "https://m/a.tgz", dest)
        assertTrue(direct.none { it == "-x" })
    }
}
