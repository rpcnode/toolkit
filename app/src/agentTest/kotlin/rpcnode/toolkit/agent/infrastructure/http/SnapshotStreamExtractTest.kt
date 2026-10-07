package rpcnode.toolkit.agent.infrastructure.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotStreamExtractTest
{
    @Test
    fun flatten_moves_nested_output_directory_inside_dest()
    {
        val dest = Files.createTempDirectory("snap-dest")
        val nested = dest.resolve("output-directory")
        Files.createDirectories(nested.resolve("database"))
        Files.writeString(nested.resolve("database").resolve("x.db"), "data")

        SnapshotStreamExtract().flattenNestedOutputDirectory(dest)

        assertTrue(Files.isRegularFile(dest.resolve("database").resolve("x.db")))
        assertFalse(Files.exists(nested))
        assertEquals("data", Files.readString(dest.resolve("database").resolve("x.db")))
    }

    @Test
    fun stream_unpack_preserves_client_jar_and_clears_chain_data()
    {
        val root = Files.createTempDirectory("snap-stream")
        val payload = root.resolve("payload")
        Files.createDirectories(payload.resolve("output-directory").resolve("database"))
        Files.writeString(payload.resolve("output-directory").resolve("database").resolve("x.db"), "streamed")
        val archive = root.resolve("snap.tgz")
        val pack = ProcessBuilder("tar", "-czf", archive.toString(), "-C", payload.toString(), ".")
            .redirectErrorStream(true)
            .start()
        assertEquals(0, pack.waitFor(), pack.inputStream.bufferedReader().readText())

        val bytes = Files.readAllBytes(archive)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/snap.tgz") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
        try
        {
            val dest = root.resolve("dest")
            Files.createDirectories(dest)
            Files.writeString(dest.resolve("FullNode.jar"), "jar-bytes")
            Files.writeString(dest.resolve("config.conf"), "conf")
            Files.writeString(dest.resolve("VERSION"), "1.0\n")
            Files.createDirectories(dest.resolve("database"))
            Files.writeString(dest.resolve("database").resolve("old.db"), "old")
            Files.createDirectories(dest.resolve(".toolkit"))
            Files.writeString(dest.resolve(".toolkit").resolve("launch.json"), "{}")

            val url = "http://127.0.0.1:${server.address.port}/snap.tgz"
            SnapshotStreamExtract().fetchAndExtract(
                label = "test-stream",
                url = url,
                destDir = dest,
                expectedBytes = bytes.size.toLong(),
                streamUnpack = true,
            ) { _, _ -> }

            assertEquals("jar-bytes", Files.readString(dest.resolve("FullNode.jar")))
            assertEquals("conf", Files.readString(dest.resolve("config.conf")))
            assertEquals("1.0", Files.readString(dest.resolve("VERSION")).trim())
            assertTrue(Files.isRegularFile(dest.resolve(".toolkit").resolve("launch.json")))
            assertTrue(Files.isRegularFile(dest.resolve("database").resolve("x.db")))
            assertFalse(Files.exists(dest.resolve("database").resolve("old.db")))
            assertFalse(Files.exists(dest.resolve(".toolkit").resolve(SnapshotStreamExtract.ARCHIVE_NAME)))
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun stream_unpack_survives_a_dropped_connection()
    {
        val root = Files.createTempDirectory("snap-resume")
        val payload = root.resolve("payload")
        Files.createDirectories(payload.resolve("output-directory").resolve("database"))
        val rnd = java.util.Random(3)
        repeat(6) { i ->
            val chunk = ByteArray(1024 * 1024).also { rnd.nextBytes(it) }
            Files.write(payload.resolve("output-directory").resolve("database").resolve("part$i.bin"), chunk)
        }
        val archive = root.resolve("snap.tgz")
        val pack = ProcessBuilder("tar", "-czf", archive.toString(), "-C", payload.toString(), ".")
            .redirectErrorStream(true)
            .start()
        assertEquals(0, pack.waitFor(), pack.inputStream.bufferedReader().readText())
        val bytes = Files.readAllBytes(archive)

        val calls = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/snap.tgz") { ex ->
            val n = calls.incrementAndGet()
            val start = ex.requestHeaders.getFirst("Range")?.substringAfter("bytes=")?.substringBefore("-")?.toIntOrNull() ?: 0
            val rest = bytes.size - start
            if (start > 0)
            {
                ex.responseHeaders.add("Content-Range", "bytes $start-${bytes.size - 1}/${bytes.size}")
                ex.sendResponseHeaders(206, rest.toLong())
            }
            else
            {
                ex.sendResponseHeaders(200, rest.toLong())
            }
            if (n == 1)
            {
                ex.responseBody.write(bytes, start, rest / 2)
                ex.responseBody.flush()
                ex.close() // network drops half way
            }
            else
            {
                ex.responseBody.use { it.write(bytes, start, rest) }
            }
        }
        server.executor = Executors.newFixedThreadPool(2)
        server.start()
        try
        {
            val dest = root.resolve("dest")
            Files.createDirectories(dest)
            Files.writeString(dest.resolve("FullNode.jar"), "jar-bytes")
            val retries = mutableListOf<String>()
            SnapshotStreamExtract(
                streamCopier = { url, expected ->
                    ResumableStreamCopier(url, expected, backoffMs = { 1L }, sleep = { Thread.sleep(1) })
                },
            ).fetchAndExtract(
                label = "test-resume",
                url = "http://127.0.0.1:${server.address.port}/snap.tgz",
                destDir = dest,
                expectedBytes = bytes.size.toLong(),
                streamUnpack = true,
                onRetry = { _, _, why -> retries += why },
            ) { _, _ -> }

            assertTrue(retries.isNotEmpty(), "the drop must have been retried")
            assertTrue(calls.get() >= 2)
            assertEquals("jar-bytes", Files.readString(dest.resolve("FullNode.jar")))
            for (i in 0 until 6)
            {
                assertEquals(
                    1024 * 1024L,
                    Files.size(dest.resolve("database").resolve("part$i.bin")),
                    "part$i.bin intact after resume",
                )
            }
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun archive_extract_reports_progress_and_keeps_client_jar()
    {
        val root = Files.createTempDirectory("snap-extract")
        val payload = root.resolve("payload")
        Files.createDirectories(payload.resolve("database"))
        // incompressible-ish data so the .tgz is large enough for tar to take several reads
        val rnd = java.util.Random(1)
        repeat(8) { i ->
            val chunk = ByteArray(1024 * 1024).also { rnd.nextBytes(it) }
            Files.write(payload.resolve("database").resolve("part$i.bin"), chunk)
        }
        val archive = root.resolve("snap.tgz")
        val pack = ProcessBuilder("tar", "-czf", archive.toString(), "-C", payload.toString(), ".")
            .redirectErrorStream(true)
            .start()
        assertEquals(0, pack.waitFor(), pack.inputStream.bufferedReader().readText())
        val bytes = Files.readAllBytes(archive)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/snap.tgz") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
        try
        {
            val dest = root.resolve("dest")
            Files.createDirectories(dest)
            Files.writeString(dest.resolve("FullNode.jar"), "jar-bytes")

            val phases = mutableListOf<String>()
            val extractProgress = mutableListOf<Pair<Long, Long?>>()
            var phase = ""
            SnapshotStreamExtract().fetchAndExtract(
                label = "test-extract",
                url = "http://127.0.0.1:${server.address.port}/snap.tgz",
                destDir = dest,
                expectedBytes = bytes.size.toLong(),
                streamUnpack = false,
                onPhase = { p ->
                    phase = p
                    phases += p
                },
            ) { copied, total ->
                if (phase == "extract")
                {
                    extractProgress += copied to total
                }
            }

            assertEquals(listOf("download", "extract"), phases)
            assertTrue(extractProgress.size >= 2, "extract must report progress, got $extractProgress")
            assertEquals(0L, extractProgress.first().first)
            assertEquals(bytes.size.toLong(), extractProgress.last().second)
            assertEquals(bytes.size.toLong(), extractProgress.last().first)
            assertTrue(extractProgress.map { it.first }.zipWithNext().all { (a, b) -> a <= b }, "monotonic")
            assertEquals("jar-bytes", Files.readString(dest.resolve("FullNode.jar")))
            assertEquals(1024 * 1024L, Files.size(dest.resolve("database").resolve("part0.bin")))
        }
        finally
        {
            server.stop(0)
        }
    }

    @Test
    fun preserve_helper_keeps_client_files_not_data_dirs()
    {
        val dir = Files.createTempDirectory("snap-preserve")
        val jar = dir.resolve("FullNode.jar")
        Files.writeString(jar, "x")
        val data = dir.resolve("database")
        Files.createDirectories(data)
        assertTrue(SnapshotStreamExtract.shouldPreserveAlongsideSnapshot(jar))
        assertFalse(SnapshotStreamExtract.shouldPreserveAlongsideSnapshot(data))
        assertTrue(
            SnapshotStreamExtract.shouldPreserveAlongsideSnapshot(
                dir.resolve(".toolkit").also { Files.createDirectories(it) },
            ),
        )
    }
}
