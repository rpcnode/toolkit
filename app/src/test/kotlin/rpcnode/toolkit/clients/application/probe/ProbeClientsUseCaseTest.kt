package rpcnode.toolkit.clients.application.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeClientProgramCatalog
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.FakeGitHubReleaseClient
import rpcnode.toolkit.clients.FakeGitHubTokenProvider
import rpcnode.toolkit.clients.application.probeone.ProbeClientProgramUseCase
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientRelease
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.model.ClientVersionSource
import rpcnode.toolkit.clients.infrastructure.tracking.InMemoryClientPreviewStore

class ProbeClientsUseCaseTest
{
    @Test
    fun without_a_token_does_not_touch_the_database() = runTest {
        val repo = FakeClientVersionRepository(
            seed = listOf(ClientVersionPin(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin", currentVersion = "29.3")),
        )
        val result = useCase(repo, token = null)()
        assertIs<ProbeClientsResult.TokenRequired>(result)
        assertEquals("", repo.find(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin")?.latestVersion.orEmpty())
    }

    @Test
    fun walks_added_db_rows_in_parallel_and_writes_latest() = runTest {
        val repo = FakeClientVersionRepository(
            seed = listOf(
                ClientVersionPin(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin", currentVersion = "29.3"),
                ClientVersionPin(NetworkId.TRON, EnvId.MAINNET, "FullNode.jar", currentVersion = "4.8.2.1"),
            ),
        )
        val result = useCase(repo)()
        assertIs<ProbeClientsResult.Done>(result)
        assertEquals("29.4", repo.find(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin")?.latestVersion)
        assertEquals("4.8.3", repo.find(NetworkId.TRON, EnvId.MAINNET, "FullNode.jar")?.latestVersion)
        assertEquals("29.3", repo.find(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin")?.currentVersion)
        assertEquals("4.8.2.1", repo.find(NetworkId.TRON, EnvId.MAINNET, "FullNode.jar")?.currentVersion)
    }

    @Test
    fun skips_pins_that_were_never_downloaded() = runTest {
        val repo = FakeClientVersionRepository(
            seed = listOf(ClientVersionPin(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin", currentVersion = "")),
        )
        useCase(repo)()
        assertEquals("", repo.find(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin")?.latestVersion.orEmpty())
    }

    private fun useCase(repo: FakeClientVersionRepository, token: String? = "tok"): ProbeClientsUseCase
    {
        val catalog = FakeClientProgramCatalog(
            listOf(
                ClientProgramSpec(
                    network = NetworkId.BITCOIN,
                    env = EnvId.MAINNET,
                    programId = "bitcoin",
                    source = ClientVersionSource.GitHubRelease(repo = "bitcoin/bitcoin"),
                ),
                ClientProgramSpec(
                    network = NetworkId.TRON,
                    env = EnvId.MAINNET,
                    programId = "FullNode.jar",
                    source = ClientVersionSource.Pinned(version = "4.8.2.1", tag = "GreatVoyage-v4.8.2.1", label = "tron"),
                ),
            ),
        )
        val probeOne = ProbeClientProgramUseCase(
            versionRepository = repo,
            githubReleaseClient = FakeGitHubReleaseClient(),
            previewStore = InMemoryClientPreviewStore(),
            clientReleaseResolvers = mapOf(
                NetworkId.BITCOIN to ClientReleaseResolver {
                    ClientRelease(version = "29.4", tag = "v29.4", sourceLabel = "bitcoin/bitcoin")
                },
                NetworkId.TRON to ClientReleaseResolver {
                    ClientRelease(version = "4.8.3", tag = "GreatVoyage-v4.8.3", sourceLabel = "tronprotocol/java-tron")
                },
            ),
        )
        return ProbeClientsUseCase(
            versionRepository = repo,
            programCatalog = catalog,
            probeOne = probeOne,
            tokenProvider = FakeGitHubTokenProvider(token),
            concurrency = Semaphore(4),
        )
    }
}
