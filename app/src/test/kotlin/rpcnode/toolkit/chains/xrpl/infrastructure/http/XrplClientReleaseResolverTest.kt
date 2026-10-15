package rpcnode.toolkit.chains.xrpl.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.FakeGitHubReleaseClient
import rpcnode.toolkit.clients.application.GitHubRelease

class XrplClientReleaseResolverTest
{
    private fun resolver(latest: String) = XrplClientReleaseResolver(
        FakeGitHubReleaseClient(mapOf("XRPLF/rippled" to GitHubRelease(tag = latest, version = latest))),
    )

    @Test
    fun takes_the_latest_github_release_even_when_the_apt_pool_lacks_it() = runTest {
        val r = resolver("3.4.1").resolve(EnvId.MAINNET)
        assertEquals("3.4.1", r?.version)
        assertEquals("3.4.1", r?.tag)
    }

    @Test
    fun never_picks_broken_3_2() = runTest {
        assertEquals("3.4.1", resolver("3.2.1").resolve(EnvId.TESTNET)?.version)
        assertEquals("3.4.1", resolver("3.2.0").resolve(EnvId.MAINNET)?.version)
    }
}
