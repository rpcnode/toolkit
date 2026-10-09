package rpcnode.toolkit.networks.infrastructure.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class HttpDirectoryListingResolverTest
{
    private val pattern = Regex("backup[0-9]{8}")
    private val archiveName = "FullNode_output-directory.tgz"

    @Test
    fun a_direct_archive_link_passes_through_unscraped() = runTest {
        val resolver = HttpDirectoryListingResolver()
        val url = resolver.latestArchiveUrl(
            "https://snapshots.example/backup20260808/FullNode_output-directory.tgz",
            pattern,
            archiveName,
        )
        assertEquals("https://snapshots.example/backup20260808/FullNode_output-directory.tgz", url)
    }

    @Test
    fun a_blank_root_resolves_to_null() = runTest {
        val resolver = HttpDirectoryListingResolver()
        assertNull(resolver.latestArchiveUrl("   ", pattern, archiveName))
    }

    @Test
    fun scrapes_the_newest_entry_matching_the_pattern() = runTest {
        val server = startListingServer(
            """
            <html><body>
            <a href="backup20260825/">backup20260825/</a>
            <a href="backup20260826/">backup20260826/</a>
            <a href="backup20260808/">backup20260808/</a>
            </body></html>
            """.trimIndent(),
        )
        try
        {
            val resolver = HttpDirectoryListingResolver()
            val url = resolver.latestArchiveUrl("http://127.0.0.1:${server.address.port}/", pattern, archiveName)
            assertEquals("http://127.0.0.1:${server.address.port}/backup20260826/$archiveName", url)
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun a_listing_with_no_matching_entries_resolves_to_null() = runTest {
        val server = startListingServer("<html><body>nothing here</body></html>")
        try
        {
            val resolver = HttpDirectoryListingResolver()
            assertNull(resolver.latestArchiveUrl("http://127.0.0.1:${server.address.port}/", pattern, archiveName))
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun a_fresh_scrape_is_cached_until_the_ttl_elapses() = runTest {
        val hits = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            val body = """<a href="backup20260826/">backup20260826/</a>""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try
        {
            val resolver = HttpDirectoryListingResolver(cacheTtl = Duration.ofMinutes(30))
            val root = "http://127.0.0.1:${server.address.port}/"
            resolver.latestArchiveUrl(root, pattern, archiveName)
            resolver.latestArchiveUrl(root, pattern, archiveName)
            assertEquals(1, hits.get())
        }
        finally
        {
            server.stop(0)
        }
    }

    private fun startListingServer(body: String): HttpServer
    {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }
}
