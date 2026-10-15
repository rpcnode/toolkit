package rpcnode.toolkit.clients.infrastructure.github

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.clients.FakeGitHubTokenProvider

class HttpGitHubReleaseClientPrereleaseTest
{
    // newest first, as GitHub lists them: an rc, then the stable line, one draft on top
    private val releases = """
        [
          {"tag_name":"v9.0.0","draft":true,"prerelease":false,"assets":[]},
          {"tag_name":"v8.4.0-alpha","draft":false,"prerelease":false,"assets":[]},
          {"tag_name":"v8.3.0-rc.0","draft":false,"prerelease":true,"assets":[]},
          {"tag_name":"v8.2.3","draft":false,"prerelease":false,"assets":[]}
        ]
    """.trimIndent()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/repos/sigp/lighthouse/releases") { ex ->
            val bytes = releases.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val client = HttpGitHubReleaseClient(
        tokenProvider = FakeGitHubTokenProvider(null),
        apiBase = "http://127.0.0.1:${server.address.port}",
    )

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun stable_only_by_default_so_mainnet_never_gets_a_release_candidate() = runTest {
        assertEquals("v8.2.3", client.latestRelease("sigp/lighthouse", null)?.tag)
        assertEquals("v8.2.3", client.latestRelease("sigp/lighthouse", null, includePrerelease = false)?.tag)
    }

    @Test
    fun an_alpha_published_as_a_normal_release_is_skipped_too() = runTest {
        // bnb-chain/bsc ships v1.8.0-alpha with prerelease=false; it must not become "latest"
        assertEquals("v8.2.3", client.latestRelease("sigp/lighthouse", null)?.tag)
        assertEquals("v8.4.0-alpha", client.latestRelease("sigp/lighthouse", null, includePrerelease = true)?.tag)
    }

    @Test
    fun release_candidates_count_when_asked_and_drafts_never_do() = runTest {
        assertEquals("v8.4.0-alpha", client.latestRelease("sigp/lighthouse", null, includePrerelease = true)?.tag)
    }

    @Test
    fun unknown_repo_is_just_null() = runTest {
        assertNull(client.latestRelease("nobody/none", null, includePrerelease = true))
    }
}
