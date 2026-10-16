package rpcnode.toolkit.chains.avalanche.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseClient

class AvalancheClientReleaseResolverTest
{
    private class Recording : GitHubReleaseClient
    {
        val asked = mutableListOf<Pair<String, Boolean>>()
        override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease? =
            latestRelease(repo, tagPrefix, false)

        override suspend fun latestRelease(repo: String, tagPrefix: String?, includePrerelease: Boolean): GitHubRelease?
        {
            asked += repo to includePrerelease
            return if (includePrerelease) GitHubRelease(tag = "v1.15.2-fuji", version = "1.15.2-fuji")
            else GitHubRelease(tag = "v1.15.1", version = "1.15.1")
        }
    }

    @Test
    fun mainnet_takes_the_stable_release_and_fuji_also_the_fuji_pre_release() = runTest {
        val github = Recording()
        val resolver = AvalancheClientReleaseResolver(github)

        assertEquals("v1.15.1", resolver.resolve(EnvId.MAINNET)?.tag)
        assertEquals("v1.15.2-fuji", resolver.resolve(EnvId.FUJI)?.tag)
        assertEquals(listOf("ava-labs/avalanchego" to false, "ava-labs/avalanchego" to true), github.asked)
    }

    @Test
    fun other_envs_are_not_served() = runTest {
        assertNull(AvalancheClientReleaseResolver(Recording()).resolve(EnvId.TESTNET))
    }
}
