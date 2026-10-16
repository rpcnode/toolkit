package rpcnode.toolkit.chains.polygon.infrastructure.http

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

class PolygonClientReleaseResolverTest
{
    private fun release(tag: String, vararg assets: String) =
        GitHubRelease(tag = tag, version = tag.removePrefix("v"), assets = assets.map { GitHubReleaseAsset(it, "https://x/$it") })

    /** Newest first, honouring [ReleaseFilter.requireAsset] like the HTTP client does. */
    private class Feed(private val releases: List<GitHubRelease>) : GitHubReleaseClient
    {
        override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease? = releases.firstOrNull()

        override suspend fun latestRelease(repo: String, filter: ReleaseFilter): GitHubRelease? =
            releases.firstOrNull { r -> filter.requireAsset == null || r.assets.any { assetGlobMatches(filter.requireAsset, it.name) } }
    }

    private val feed = Feed(
        listOf(
            release("v2.11.0", "bor-amoy-sentry-config_v2.11.0-all.deb"),
            release("v2.10.2", "bor-amoy-sentry-config_v2.10.2-all.deb", "bor-mainnet-sentry-config_v2.10.2-all.deb"),
        ),
    )

    @Test
    fun a_tag_without_the_env_config_deb_is_not_offered() = runTest {
        val resolver = PolygonClientReleaseResolver(feed)
        assertEquals("v2.10.2", resolver.resolve(EnvId.MAINNET)?.tag)
        assertEquals("v2.11.0", resolver.resolve(EnvId.AMOY)?.tag)
    }

    @Test
    fun other_envs_are_not_served() = runTest {
        assertNull(PolygonClientReleaseResolver(feed).resolve(EnvId.SEPOLIA))
    }
}
