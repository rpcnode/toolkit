package rpcnode.toolkit.clients.application.probeone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.FakeGitHubReleaseClient
import rpcnode.toolkit.clients.application.ClientProgramKey
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientRelease
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.model.ClientVersionSource
import rpcnode.toolkit.clients.infrastructure.tracking.InMemoryClientPreviewStore

class ProbeClientProgramUseCaseTest
{
    @Test
    fun pinned_source_probes_instantly_with_no_error() = runTest {
        val previewStore = InMemoryClientPreviewStore()
        val useCase = ProbeClientProgramUseCase(FakeClientVersionRepository(), FakeGitHubReleaseClient(), previewStore)
        val spec = ClientProgramSpec(
            network = NetworkId.TRON,
            env = EnvId.MAINNET,
            programId = "FullNode.jar",
            source = ClientVersionSource.Pinned(version = "4.8.2.1", tag = "GreatVoyage-v4.8.2.1", label = "tronprotocol/java-tron"),
        )

        val pin = useCase(spec)

        assertEquals("4.8.2.1", pin.latestVersion)
        assertEquals("", pin.probeError)
        assertNotNull(previewStore.get(ClientProgramKey(NetworkId.TRON, EnvId.MAINNET, "FullNode.jar")))
    }

    @Test
    fun github_release_not_found_records_a_probe_error_in_preview() = runTest {
        val previewStore = InMemoryClientPreviewStore()
        val useCase = ProbeClientProgramUseCase(FakeClientVersionRepository(), FakeGitHubReleaseClient(), previewStore)
        val spec = ClientProgramSpec(
            network = NetworkId.BITCOIN,
            env = EnvId.MAINNET,
            programId = "bitcoin",
            source = ClientVersionSource.GitHubRelease(repo = "bitcoin/bitcoin"),
        )

        val pin = useCase(spec)

        assertEquals("", pin.latestVersion)
        assertNotEmptyProbeError(pin)
    }

    @Test
    fun github_release_found_updates_latest_and_never_inserts_a_db_row() = runTest {
        val previewStore = InMemoryClientPreviewStore()
        val githubClient = FakeGitHubReleaseClient(mapOf("bitcoin/bitcoin" to GitHubRelease(tag = "v29.4", version = "29.4")))
        val repo = FakeClientVersionRepository()
        val useCase = ProbeClientProgramUseCase(repo, githubClient, previewStore)
        val spec = ClientProgramSpec(
            network = NetworkId.BITCOIN,
            env = EnvId.MAINNET,
            programId = "bitcoin",
            source = ClientVersionSource.GitHubRelease(repo = "bitcoin/bitcoin"),
        )

        val pin = useCase(spec)

        assertEquals("29.4", pin.latestVersion)
        assertNull(repo.find(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin"))
    }

    @Test
    fun probing_an_already_synced_program_updates_the_existing_db_row_via_apply_probe() = runTest {
        val repo = FakeClientVersionRepository(
            seed = listOf(ClientVersionPin(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin", currentVersion = "29.3")),
        )
        val githubClient = FakeGitHubReleaseClient(mapOf("bitcoin/bitcoin" to GitHubRelease(tag = "v29.4", version = "29.4")))
        val useCase = ProbeClientProgramUseCase(repo, githubClient, InMemoryClientPreviewStore())
        val spec = ClientProgramSpec(
            network = NetworkId.BITCOIN,
            env = EnvId.MAINNET,
            programId = "bitcoin",
            source = ClientVersionSource.GitHubRelease(repo = "bitcoin/bitcoin"),
        )

        useCase(spec)

        val updated = repo.find(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin")
        assertEquals("29.3", updated?.currentVersion)
        assertEquals("29.4", updated?.latestVersion)
    }

    @Test
    fun a_network_resolver_overrides_the_yaml_source_on_an_existing_row() = runTest {
        val repo = FakeClientVersionRepository(
            seed = listOf(ClientVersionPin(NetworkId.TRON, EnvId.MAINNET, "FullNode.jar", currentVersion = "4.8.2.1")),
        )
        val spec = ClientProgramSpec(
            network = NetworkId.TRON,
            env = EnvId.MAINNET,
            programId = "FullNode.jar",
            source = ClientVersionSource.Pinned(version = "4.8.2.1", tag = "GreatVoyage-v4.8.2.1", label = "tron"),
        )
        val useCase = ProbeClientProgramUseCase(
            versionRepository = repo,
            githubReleaseClient = FakeGitHubReleaseClient(),
            previewStore = InMemoryClientPreviewStore(),
            clientReleaseResolvers = mapOf(
                NetworkId.TRON to ClientReleaseResolver {
                    ClientRelease(version = "4.8.3", tag = "GreatVoyage-v4.8.3", sourceLabel = "tronprotocol/java-tron")
                },
            ),
        )

        useCase(spec)

        val updated = repo.find(NetworkId.TRON, EnvId.MAINNET, "FullNode.jar")
        assertEquals("4.8.2.1", updated?.currentVersion)
        assertEquals("4.8.3", updated?.latestVersion)
    }

    private fun assertNotEmptyProbeError(pin: ClientVersionPin)
    {
        kotlin.test.assertTrue(pin.probeError.isNotBlank())
    }
}
