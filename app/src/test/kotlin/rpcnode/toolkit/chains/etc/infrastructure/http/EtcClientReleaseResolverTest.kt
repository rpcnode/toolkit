package rpcnode.toolkit.chains.etc.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseClient

class EtcClientReleaseResolverTest
{
    private class Recording : GitHubReleaseClient
    {
        val asked = mutableListOf<String>()
        override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease?
        {
            asked += repo
            return GitHubRelease(tag = "v1.13.0", version = "1.13.0")
        }

        override suspend fun latestRelease(repo: String, tagPrefix: String?, includePrerelease: Boolean): GitHubRelease? =
            latestRelease(repo, tagPrefix)
    }

    @Test
    fun both_envs_follow_the_stable_core_geth_release() = runTest {
        val github = Recording()
        val resolver = EtcClientReleaseResolver(github)
        for (env in listOf(EnvId.MAINNET, EnvId.MORDOR))
        {
            val r = resolver.resolve(env)!!
            assertEquals("v1.13.0", r.tag)
            assertEquals("ethereumclassic/core-geth", r.sourceLabel)
        }
        assertEquals(listOf("ethereumclassic/core-geth", "ethereumclassic/core-geth"), github.asked)
    }

    @Test
    fun other_envs_are_not_served() = runTest {
        assertNull(EtcClientReleaseResolver(Recording()).resolve(EnvId.TESTNET))
    }
}
