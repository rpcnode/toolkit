package rpcnode.toolkit.chains.bsc.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseAsset
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.ReleaseFilter
import rpcnode.toolkit.clients.application.assetGlobMatches

class BscClientReleaseResolverTest
{
    private fun release(tag: String, vararg assets: String) =
        GitHubRelease(tag = tag, version = tag.removePrefix("v"), assets = assets.map { GitHubReleaseAsset(it, "https://x/$it") })

    private class Feed(private val releases: List<GitHubRelease>) : GitHubReleaseClient
    {
        override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease? = releases.firstOrNull()

        override suspend fun latestRelease(repo: String, filter: ReleaseFilter): GitHubRelease? =
            releases.firstOrNull { r -> filter.requireAsset == null || r.assets.any { assetGlobMatches(filter.requireAsset, it.name) } }
    }

    private val feed = Feed(
        listOf(
            release("v1.9.0", "geth_linux", "testnet.zip"),
            release("v1.8.1", "geth_linux", "mainnet.zip", "testnet.zip"),
        ),
    )

    @Test
    fun a_tag_without_the_env_zip_is_not_offered() = runTest {
        val resolver = BscClientReleaseResolver(feed)
        assertEquals("v1.8.1", resolver.resolve(EnvId.MAINNET)?.tag)
        assertEquals("v1.9.0", resolver.resolve(EnvId.TESTNET)?.tag)
    }

    @Test
    fun other_envs_are_not_served() = runTest {
        assertNull(BscClientReleaseResolver(feed).resolve(EnvId.SEPOLIA))
    }
}
