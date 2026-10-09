package rpcnode.toolkit.chains.tron.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.FakeGitHubReleaseClient
import rpcnode.toolkit.clients.application.GitHubRelease

class TronClientReleaseResolverTest
{
    @Test
    fun mainnet_and_shasta_use_java_tron() = runTest {
        val github = FakeGitHubReleaseClient(
            mapOf(
                "tronprotocol/java-tron" to GitHubRelease(tag = "GreatVoyage-v4.8.2.1", version = "GreatVoyage-v4.8.2.1"),
                "tron-nile-testnet/nile-testnet" to GitHubRelease(tag = "nile-other", version = "nile-other"),
            ),
        )
        val resolver = TronClientReleaseResolver(github)
        for (env in listOf(EnvId.MAINNET, EnvId.SHASTA))
        {
            val release = resolver.resolve(env)
            assertEquals("GreatVoyage-v4.8.2.1", release?.version)
            assertEquals("tronprotocol/java-tron", release?.sourceLabel)
        }
    }

    @Test
    fun nile_uses_its_own_testnet_repo() = runTest {
        val github = FakeGitHubReleaseClient(
            mapOf(
                "tronprotocol/java-tron" to GitHubRelease(tag = "mainnet-tag", version = "mainnet-tag"),
                "tron-nile-testnet/nile-testnet" to GitHubRelease(tag = "GreatVoyage-Nile-v4.8.2.1", version = "GreatVoyage-Nile-v4.8.2.1"),
            ),
        )
        val release = TronClientReleaseResolver(github).resolve(EnvId.NILE)
        assertEquals("GreatVoyage-Nile-v4.8.2.1", release?.version)
        assertEquals("tron-nile-testnet/nile-testnet", release?.sourceLabel)
    }

    @Test
    fun an_env_tron_does_not_ship_is_null() = runTest {
        val github = FakeGitHubReleaseClient(
            mapOf("tronprotocol/java-tron" to GitHubRelease(tag = "x", version = "x")),
        )
        assertNull(TronClientReleaseResolver(github).resolve(EnvId.TESTNET4))
    }
}
