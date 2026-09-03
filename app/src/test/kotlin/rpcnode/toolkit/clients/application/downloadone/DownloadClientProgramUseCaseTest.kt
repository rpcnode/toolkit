package rpcnode.toolkit.clients.application.downloadone

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeArtifactDownloader
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.FakeGitHubReleaseClient
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientArtifactRole
import rpcnode.toolkit.clients.domain.model.ClientRelease
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.model.ClientVersionSource
import rpcnode.toolkit.clients.infrastructure.filesystem.FileClientManifestWriter
import rpcnode.toolkit.clients.infrastructure.filesystem.FileInstallPlanWriter
import rpcnode.toolkit.clients.infrastructure.tracking.InMemoryClientDownloadTracker

private val pinnedSpec = ClientProgramSpec(
    network = NetworkId.TRON,
    env = EnvId.MAINNET,
    programId = "FullNode.jar",
    source = ClientVersionSource.Pinned(version = "4.8.2.1", tag = "GreatVoyage-v4.8.2.1", label = "tronprotocol/java-tron"),
    artifacts = listOf(ClientArtifactSpec("FullNode.jar", ClientArtifactRole.ARTIFACT, "https://example.com/{tag}/FullNode.jar")),
    configs = listOf(ClientArtifactSpec("config.conf", ClientArtifactRole.CONFIG, "https://example.com/{tag}/config.conf")),
)

class DownloadClientProgramUseCaseTest
{
    @Test
    fun downloads_artifacts_and_configs_in_parallel_then_persists_the_pin() = runTest {
        val dest = Files.createTempDirectory("clients-dest")
        val downloader = FakeArtifactDownloader()
        val repo = FakeClientVersionRepository()
        val useCase = DownloadClientProgramUseCase(
            versionRepository = repo,
            githubReleaseClient = FakeGitHubReleaseClient(),
            artifactDownloader = downloader,
            tracker = InMemoryClientDownloadTracker(),
            manifestWriter = FileClientManifestWriter(),
            installPlanWriter = FileInstallPlanWriter(),
            destDir = dest,
        )

        val result = useCase(pinnedSpec)

        val ok = assertIs<DownloadClientProgramResult.Ok>(result)
        assertEquals("4.8.2.1", ok.pin.currentVersion)
        assertTrue(downloader.maxInFlight.get() >= 2, "expected both files to download concurrently")
        assertEquals(2, downloader.urls.size)

        val envDir = dest.resolve("tron").resolve("mainnet")
        assertTrue(Files.isRegularFile(envDir.resolve("manifest.json")))
        assertTrue(Files.isRegularFile(envDir.resolve("VERSION")))
        assertNotNull(repo.find(NetworkId.TRON, EnvId.MAINNET, "FullNode.jar"))
    }

    @Test
    fun a_missing_github_release_fails_without_touching_the_repository() = runTest {
        val dest = Files.createTempDirectory("clients-dest")
        val repo = FakeClientVersionRepository()
        val spec = pinnedSpec.copy(source = ClientVersionSource.GitHubRelease(repo = "bitcoin/bitcoin"))
        val useCase = DownloadClientProgramUseCase(
            versionRepository = repo,
            githubReleaseClient = FakeGitHubReleaseClient(),
            artifactDownloader = FakeArtifactDownloader(),
            tracker = InMemoryClientDownloadTracker(),
            manifestWriter = FileClientManifestWriter(),
            installPlanWriter = FileInstallPlanWriter(),
            destDir = dest,
        )

        val result = useCase(spec)

        assertIs<DownloadClientProgramResult.Failed>(result)
        assertEquals(emptyList(), repo.list())
    }

    @Test
    fun already_synced_at_the_same_version_skips_re_downloading_unless_forced() = runTest {
        val dest = Files.createTempDirectory("clients-dest")
        val repo = FakeClientVersionRepository(
            seed = listOf(ClientVersionPin(NetworkId.TRON, EnvId.MAINNET, "FullNode.jar", currentVersion = "4.8.2.1")),
        )
        val downloader = FakeArtifactDownloader()
        val useCase = DownloadClientProgramUseCase(
            versionRepository = repo,
            githubReleaseClient = FakeGitHubReleaseClient(),
            artifactDownloader = downloader,
            tracker = InMemoryClientDownloadTracker(),
            manifestWriter = FileClientManifestWriter(),
            installPlanWriter = FileInstallPlanWriter(),
            destDir = dest,
        )

        useCase(pinnedSpec, force = false)
        assertTrue(downloader.urls.isEmpty())

        useCase(pinnedSpec, force = true)
        assertEquals(2, downloader.urls.size)
    }

    @Test
    fun a_network_resolver_overrides_the_yaml_source_version() = runTest {
        val dest = Files.createTempDirectory("clients-dest")
        val downloader = FakeArtifactDownloader()
        val repo = FakeClientVersionRepository()
        val useCase = DownloadClientProgramUseCase(
            versionRepository = repo,
            githubReleaseClient = FakeGitHubReleaseClient(),
            artifactDownloader = downloader,
            tracker = InMemoryClientDownloadTracker(),
            manifestWriter = FileClientManifestWriter(),
            installPlanWriter = FileInstallPlanWriter(),
            destDir = dest,
            clientReleaseResolvers = mapOf(
                NetworkId.TRON to ClientReleaseResolver {
                    ClientRelease(version = "4.8.3", tag = "GreatVoyage-v4.8.3", sourceLabel = "tronprotocol/java-tron")
                },
            ),
        )

        val ok = assertIs<DownloadClientProgramResult.Ok>(useCase(pinnedSpec))
        assertEquals("4.8.3", ok.pin.currentVersion)
        assertEquals("GreatVoyage-v4.8.3", ok.pin.currentTag)
        assertEquals(
            setOf(
                "https://example.com/GreatVoyage-v4.8.3/FullNode.jar",
                "https://example.com/GreatVoyage-v4.8.3/config.conf",
            ),
            downloader.urls.toSet(),
        )
    }

    @Test
    fun a_download_failure_marks_the_tracker_and_leaves_the_repository_untouched() = runTest {
        val dest = Files.createTempDirectory("clients-dest")
        val repo = FakeClientVersionRepository()
        val useCase = DownloadClientProgramUseCase(
            versionRepository = repo,
            githubReleaseClient = FakeGitHubReleaseClient(),
            artifactDownloader = FakeArtifactDownloader(fail = true),
            tracker = InMemoryClientDownloadTracker(),
            manifestWriter = FileClientManifestWriter(),
            installPlanWriter = FileInstallPlanWriter(),
            destDir = dest,
        )

        val result = useCase(pinnedSpec)

        assertIs<DownloadClientProgramResult.Failed>(result)
        assertEquals(emptyList(), repo.list())
    }
}
