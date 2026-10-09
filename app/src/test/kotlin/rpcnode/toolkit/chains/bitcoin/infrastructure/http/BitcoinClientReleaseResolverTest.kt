package rpcnode.toolkit.chains.bitcoin.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.FakeGitHubReleaseClient
import rpcnode.toolkit.clients.application.GitHubRelease

class BitcoinClientReleaseResolverTest
{
    @Test
    fun every_shipped_env_uses_the_same_bitcoin_core_release() = runTest {
        val github = FakeGitHubReleaseClient(
            mapOf("bitcoin/bitcoin" to GitHubRelease(tag = "v29.4", version = "29.4")),
        )
        val resolver = BitcoinClientReleaseResolver(github)
        for (env in listOf(EnvId.MAINNET, EnvId.TESTNET4, EnvId.SIGNET, EnvId.REGTEST))
        {
            val release = resolver.resolve(env)
            assertEquals("29.4", release?.version)
            assertEquals("v29.4", release?.tag)
            assertEquals("bitcoin/bitcoin", release?.sourceLabel)
        }
    }

    @Test
    fun an_env_bitcoin_does_not_ship_is_null() = runTest {
        val github = FakeGitHubReleaseClient(
            mapOf("bitcoin/bitcoin" to GitHubRelease(tag = "v29.4", version = "29.4")),
        )
        assertNull(BitcoinClientReleaseResolver(github).resolve(EnvId.NILE))
    }

    @Test
    fun a_missing_github_release_is_null() = runTest {
        assertNull(BitcoinClientReleaseResolver(FakeGitHubReleaseClient()).resolve(EnvId.MAINNET))
    }
}
