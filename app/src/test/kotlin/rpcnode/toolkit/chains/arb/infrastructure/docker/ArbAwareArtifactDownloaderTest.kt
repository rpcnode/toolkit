package rpcnode.toolkit.chains.arb.infrastructure.docker

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.clients.FakeArtifactDownloader

class ArbAwareArtifactDownloaderTest
{
    @Test
    fun delegates_http_urls() = runTest {
        val http = FakeArtifactDownloader()
        val downloader = ArbAwareArtifactDownloader(http)
        val dest = Files.createTempDirectory("arb-dl-").resolve("x.jar")
        downloader.download("https://example.com/x.jar", dest) { _, _ -> }
        assertEquals(listOf("https://example.com/x.jar"), http.urls)
        assertTrue(Files.isRegularFile(dest))
    }
}
