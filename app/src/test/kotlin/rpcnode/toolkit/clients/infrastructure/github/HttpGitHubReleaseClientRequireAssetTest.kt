package rpcnode.toolkit.clients.infrastructure.github

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.clients.FakeGitHubTokenProvider
import rpcnode.toolkit.clients.application.ReleaseFilter

class HttpGitHubReleaseClientRequireAssetTest
{
    // heimdall-v2 v0.12.2: published with the amoy config debs only, mainnet ones missing
    private val releases = """
        [
          {"tag_name":"v0.12.2","draft":false,"prerelease":false,"assets":[
            {"name":"heimdall-amoy-sentry-config_v0.12.2-all.deb","browser_download_url":"https://x/amoy"},
            {"name":"heimdall-v0.12.2-amd64.deb","browser_download_url":"https://x/amd64"}]},
          {"tag_name":"v0.12.1","draft":false,"prerelease":false,"assets":[
            {"name":"heimdall-mainnet-sentry-config_v0.12.1-all.deb","browser_download_url":"https://x/main"},
            {"name":"heimdall-amoy-sentry-config_v0.12.1-all.deb","browser_download_url":"https://x/amoy1"}]}
        ]
    """.trimIndent()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/repos/0xPolygon/heimdall-v2/releases") { ex ->
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
    fun without_the_rule_the_newest_release_wins_even_when_its_files_are_missing() = runTest {
        assertEquals("v0.12.2", client.latestRelease("0xPolygon/heimdall-v2", ReleaseFilter())?.tag)
    }

    @Test
    fun a_release_without_the_required_asset_is_skipped() = runTest {
        val mainnet = ReleaseFilter(requireAsset = "heimdall-mainnet-sentry-config_*-all.deb")
        assertEquals("v0.12.1", client.latestRelease("0xPolygon/heimdall-v2", mainnet)?.tag)
    }

    @Test
    fun a_release_that_has_it_still_wins_when_newest() = runTest {
        val amoy = ReleaseFilter(requireAsset = "heimdall-amoy-sentry-config_*-all.deb")
        assertEquals("v0.12.2", client.latestRelease("0xPolygon/heimdall-v2", amoy)?.tag)
    }

    @Test
    fun no_release_carries_it_is_null() = runTest {
        assertNull(client.latestRelease("0xPolygon/heimdall-v2", ReleaseFilter(requireAsset = "nope-*")))
    }
}
