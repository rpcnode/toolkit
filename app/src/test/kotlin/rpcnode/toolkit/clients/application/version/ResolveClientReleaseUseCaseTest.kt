package rpcnode.toolkit.clients.application.version

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeClientProgramCatalog
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.domain.model.ClientRelease
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository

class ResolveClientReleaseUseCaseTest
{
    @Test
    fun resolves_one_env_release_for_a_network_in_the_map() = runTest {
        val bitcoin = ClientReleaseResolver { env ->
            if (env == EnvId.MAINNET)
            {
                ClientRelease(version = "29.4", tag = "v29.4", sourceLabel = "bitcoin/bitcoin")
            }
            else
            {
                null
            }
        }
        val useCase = useCase(mapOf(NetworkId.BITCOIN to bitcoin))

        val mainnet = assertIs<ResolveClientReleaseResult.Resolved>(useCase("bitcoin", "mainnet"))
        assertEquals("29.4", mainnet.release?.version)
        assertEquals("v29.4", mainnet.release?.tag)

        // Signet has no map hit — falls back to YAML github source via FakeGitHub.
        val signet = assertIs<ResolveClientReleaseResult.Resolved>(useCase("bitcoin", "signet"))
        assertEquals("29.4", signet.release?.version)
    }

    @Test
    fun falls_back_to_clients_yml_github_when_resolver_missing() = runTest {
        val result = assertIs<ResolveClientReleaseResult.Resolved>(useCase(emptyMap())("bsc", "mainnet"))
        assertEquals("1.8.0-alpha", result.release?.version)
        assertEquals("v1.8.0-alpha", result.release?.tag)
        assertEquals("bnb-chain/bsc", result.release?.sourceLabel)
    }

    @Test
    fun bsc_resolver_wins_when_present() = runTest {
        val bsc = ClientReleaseResolver {
            ClientRelease(version = "1.7.8", tag = "v1.7.8", sourceLabel = "bnb-chain/bsc")
        }
        val result = assertIs<ResolveClientReleaseResult.Resolved>(
            useCase(mapOf(NetworkId.BSC to bsc))("bsc", "mainnet"),
        )
        assertEquals("1.7.8", result.release?.version)
    }

    @Test
    fun unknown_network_is_rejected() = runTest {
        assertIs<ResolveClientReleaseResult.UnknownNetwork>(useCase(emptyMap())("does-not-exist", "mainnet"))
    }

    @Test
    fun env_that_the_chain_does_not_ship_is_rejected() = runTest {
        assertIs<ResolveClientReleaseResult.UnknownEnv>(useCase(emptyMap())("bitcoin", "nile"))
    }

    @Test
    fun no_program_and_no_resolver_is_null_release() = runTest {
        val result = assertIs<ResolveClientReleaseResult.Resolved>(
            useCase(emptyMap(), programs = FakeClientProgramCatalog())("tron", "shasta"),
        )
        assertNull(result.release)
    }

    private fun useCase(
        resolvers: Map<NetworkId, ClientReleaseResolver>,
        programs: ClientProgramCatalog = YamlClientProgramCatalog(),
    ) = ResolveClientReleaseUseCase(
        catalog = YamlNetworkFactsRepository(),
        clientReleaseResolvers = resolvers,
        programs = programs,
        github = object : GitHubReleaseClient
        {
            override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease? =
                when (repo)
                {
                    "bitcoin/bitcoin" -> GitHubRelease(tag = "v29.4", version = "29.4")
                    "bnb-chain/bsc" -> GitHubRelease(tag = "v1.8.0-alpha", version = "1.8.0-alpha")
                    else -> null
                }
        },
    )
}
