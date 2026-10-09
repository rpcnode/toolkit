package rpcnode.toolkit.chains.xrpl.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.FakeGitHubReleaseClient
import rpcnode.toolkit.clients.application.GitHubRelease

class XrplClientReleaseResolverTest
{
    private fun resolver(latest: String, pool: List<String>?) = XrplClientReleaseResolver(
        FakeGitHubReleaseClient(mapOf("XRPLF/rippled" to GitHubRelease(tag = latest, version = latest))),
        XrplDebPool { pool },
    )

    @Test
    fun caps_to_newest_pooled_version_when_release_is_not_packaged_yet() = runTest {
        val r = resolver("3.4.0", listOf("3.2.0", "3.2.1", "3.3.0")).resolve(EnvId.MAINNET)
        assertEquals("3.3.0", r?.version)
    }

    @Test
    fun uses_release_when_pooled() = runTest {
        val r = resolver("3.4.0", listOf("3.3.0", "3.4.0")).resolve(EnvId.TESTNET)
        assertEquals("3.4.0", r?.version)
    }

    @Test
    fun never_picks_broken_3_2() = runTest {
        val r = resolver("3.2.1", listOf("3.2.0", "3.2.1", "3.3.0")).resolve(EnvId.MAINNET)
        assertEquals("3.3.0", r?.version)
    }

    @Test
    fun trusts_github_when_pool_unreadable() = runTest {
        assertEquals("3.4.0", resolver("3.4.0", null).resolve(EnvId.MAINNET)?.version)
        assertEquals("3.3.0", resolver("3.2.1", null).resolve(EnvId.MAINNET)?.version)
    }
}
