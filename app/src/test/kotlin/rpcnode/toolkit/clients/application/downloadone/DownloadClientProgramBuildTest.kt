package rpcnode.toolkit.clients.application.downloadone

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeArtifactDownloader
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.FakeGitHubReleaseClient
import rpcnode.toolkit.clients.application.ClientBuildRequest
import rpcnode.toolkit.clients.application.ClientBuildResult
import rpcnode.toolkit.clients.application.ClientBuilder
import rpcnode.toolkit.clients.application.build.FixedHosts
import rpcnode.toolkit.clients.application.build.builtSpec
import rpcnode.toolkit.clients.application.build.host
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.infrastructure.filesystem.FileClientManifestWriter
import rpcnode.toolkit.clients.infrastructure.filesystem.FileInstallPlanWriter
import rpcnode.toolkit.clients.infrastructure.tracking.InMemoryClientDownloadTracker

/** The panel compiles a client when a release is added, and publishes the binary beside the source. */
class DownloadClientProgramBuildTest
{
    private class FakeBuilder(private val result: (ClientBuildRequest) -> ClientBuildResult) : ClientBuilder
    {
        val requests = mutableListOf<ClientBuildRequest>()

        override suspend fun build(request: ClientBuildRequest, onLog: (String) -> Unit): ClientBuildResult
        {
            requests += request
            val r = result(request)
            if (r is ClientBuildResult.Built)
            {
                r.names.forEach { Files.writeString(request.outDir.resolve(it), "bin-$it-${request.tag}") }
            }
            return r
        }
    }

    private fun useCase(
        dest: Path,
        repo: FakeClientVersionRepository,
        builder: ClientBuilder?,
        fleetArches: List<String>? = null,
        version: String = "3.4.1",
    ) = DownloadClientProgramUseCase(
        versionRepository = repo,
        githubReleaseClient = FakeGitHubReleaseClient(),
        artifactDownloader = FakeArtifactDownloader(),
        tracker = InMemoryClientDownloadTracker(),
        manifestWriter = FileClientManifestWriter(),
        installPlanWriter = FileInstallPlanWriter(),
        destDir = dest,
        clientReleaseResolvers = mapOf(
            NetworkId.XRPL to ClientReleaseResolver { ClientRelease(version, version, "test") },
        ),
        builder = builder,
        buildFleet = fleetArches?.let { arches -> FixedHosts(arches.mapIndexed { i, a -> host("h$i", a) }) },
    )

    private fun envDir(dest: Path) = dest.resolve("xrpl").resolve("mainnet")

    @Test
    fun the_built_binary_is_published_beside_the_source_and_listed_per_architecture_in_the_plan()
    {
        runTest {
            val dest = Files.createTempDirectory("clients")
            val builder = FakeBuilder { ClientBuildResult.Built(listOf("xrpld-x86_64")) }
            val repo = FakeClientVersionRepository()

            assertIs<DownloadClientProgramResult.Ok>(useCase(dest, repo, builder, listOf("x86_64")).invoke(builtSpec()))

            assertEquals(listOf(listOf("linux/amd64")), builder.requests.map { it.platforms })
            assertEquals("bin-xrpld-x86_64-3.4.1", Files.readString(envDir(dest).resolve("xrpld-x86_64")))
            assertTrue(Files.isRegularFile(envDir(dest).resolve("xrpld-src.tar.gz")), "the source stays for hosts that cannot run it")
            val plan = Files.readString(envDir(dest).resolve("install-plan.yml"))
            assertTrue(plan.contains("xrpld-x86_64") && plan.contains("x86_64"), plan)
            assertNotNull(repo.find(NetworkId.XRPL, EnvId.MAINNET, "xrpld"))
        }
    }

    @Test
    fun only_the_architectures_of_the_fleet_are_compiled()
    {
        runTest {
            val builder = FakeBuilder { ClientBuildResult.Built(listOf("xrpld-aarch64")) }
            useCase(Files.createTempDirectory("clients"), FakeClientVersionRepository(), builder, listOf("aarch64", "aarch64"))
                .invoke(builtSpec())
            assertEquals(listOf(listOf("linux/arm64")), builder.requests.map { it.platforms })
        }
    }

    @Test
    fun an_empty_fleet_compiles_nothing_and_publishes_the_source_only()
    {
        runTest {
            val builder = FakeBuilder { error("must not build") }
            val dest = Files.createTempDirectory("clients")
            assertIs<DownloadClientProgramResult.Ok>(useCase(dest, FakeClientVersionRepository(), builder, emptyList()).invoke(builtSpec()))
            assertTrue(builder.requests.isEmpty())
            assertFalse(Files.exists(envDir(dest).resolve("xrpld-x86_64")))
        }
    }

    @Test
    fun a_failed_build_fails_the_download_and_leaves_the_previous_release_untouched()
    {
        runTest {
            val dest = Files.createTempDirectory("clients")
            Files.createDirectories(envDir(dest))
            Files.writeString(envDir(dest).resolve("xrpld-x86_64"), "old-binary")
            val old = ClientVersionPin(NetworkId.XRPL, EnvId.MAINNET, "xrpld", currentVersion = "3.3.0")
            val repo = FakeClientVersionRepository(listOf(old))

            val result = useCase(dest, repo, FakeBuilder { ClientBuildResult.Failed("cargo exploded") }, listOf("x86_64")).invoke(builtSpec())

            val failed = assertIs<DownloadClientProgramResult.Failed>(result)
            assertTrue(failed.error.contains("cargo exploded"), failed.error)
            assertEquals("old-binary", Files.readString(envDir(dest).resolve("xrpld-x86_64")))
            assertEquals("3.3.0", repo.find(NetworkId.XRPL, EnvId.MAINNET, "xrpld")?.currentVersion, "the pin does not move")
        }
    }

    @Test
    fun without_a_docker_capable_host_the_source_is_published_and_a_stale_binary_is_removed()
    {
        runTest {
            val dest = Files.createTempDirectory("clients")
            Files.createDirectories(envDir(dest))
            Files.writeString(envDir(dest).resolve("xrpld-x86_64"), "binary-of-3.3.0")
            val old = ClientVersionPin(NetworkId.XRPL, EnvId.MAINNET, "xrpld", currentVersion = "3.3.0")

            val result = useCase(
                dest, FakeClientVersionRepository(listOf(old)),
                FakeBuilder { ClientBuildResult.Unavailable("no docker") }, listOf("x86_64"),
            ).invoke(builtSpec())

            assertIs<DownloadClientProgramResult.Ok>(result)
            assertFalse(Files.exists(envDir(dest).resolve("xrpld-x86_64")), "a 3.3.0 binary must not ride along with 3.4.1 source")
        }
    }

    @Test
    fun a_new_architecture_of_the_same_release_is_added_without_dropping_the_others()
    {
        runTest {
            val dest = Files.createTempDirectory("clients")
            val repo = FakeClientVersionRepository()
            val builder = FakeBuilder { r -> ClientBuildResult.Built(r.platforms.orEmpty().map { if (it.endsWith("arm64")) "xrpld-aarch64" else "xrpld-x86_64" }) }
            val uc = useCase(dest, repo, builder, listOf("x86_64"))

            uc.invoke(builtSpec())
            assertEquals(listOf(listOf("linux/amd64")), builder.requests.map { it.platforms })

            // a node on aarch64 is being created: same release, one more architecture
            uc.invoke(builtSpec(), force = false, requiredArch = "aarch64")

            assertEquals(2, builder.requests.size)
            assertEquals(listOf("linux/arm64"), builder.requests.last().platforms?.filter { it.endsWith("arm64") })
            assertTrue(Files.exists(envDir(dest).resolve("xrpld-x86_64")), "x86_64 is kept")
            assertTrue(Files.exists(envDir(dest).resolve("xrpld-aarch64")))

            // everything is there now: nothing more is compiled, not even by a forced re-download
            uc.invoke(builtSpec(), force = false, requiredArch = "aarch64")
            uc.invoke(builtSpec(), force = true, requiredArch = "aarch64")
            assertEquals(2, builder.requests.size)
        }
    }

    @Test
    fun a_new_release_drops_the_binaries_of_the_old_one()
    {
        runTest {
            val dest = Files.createTempDirectory("clients")
            val repo = FakeClientVersionRepository()
            val builder = FakeBuilder { ClientBuildResult.Built(listOf("xrpld-x86_64")) }

            useCase(dest, repo, builder, listOf("x86_64"), version = "3.3.0").invoke(builtSpec())
            assertEquals("bin-xrpld-x86_64-3.3.0", Files.readString(envDir(dest).resolve("xrpld-x86_64")))

            useCase(dest, repo, builder, listOf("x86_64"), version = "3.4.1").invoke(builtSpec())
            assertEquals("bin-xrpld-x86_64-3.4.1", Files.readString(envDir(dest).resolve("xrpld-x86_64")))
        }
    }

    @Test
    fun a_stale_pin_of_another_release_line_does_not_block_the_right_pinned_version()
    {
        runTest {
            // Optimism: op-node's pin once held op-reth's number (2.6.0); the real op-node pin 1.19.9 is "older" by number only.
            val dest = Files.createTempDirectory("clients")
            val stale = ClientVersionPin(
                NetworkId.OPTIMISM, EnvId.SEPOLIA, "op-node", currentVersion = "2.6.0", currentTag = "op-reth/v2.6.0",
            )
            val repo = FakeClientVersionRepository(listOf(stale))
            val spec = builtSpec().copy(
                network = NetworkId.OPTIMISM, env = EnvId.SEPOLIA, programId = "op-node",
                source = rpcnode.toolkit.clients.domain.model.ClientVersionSource.Pinned("1.19.9", "op-node/v1.19.9", "monorepo"),
                build = rpcnode.toolkit.clients.domain.model.ClientBuildSpec("op-node.Dockerfile", listOf("op-node"), listOf("linux/amd64")),
                artifacts = emptyList(),
            )
            val builder = FakeBuilder { ClientBuildResult.Built(listOf("op-node-x86_64")) }
            val uc = DownloadClientProgramUseCase(
                versionRepository = repo,
                githubReleaseClient = FakeGitHubReleaseClient(),
                artifactDownloader = FakeArtifactDownloader(),
                tracker = InMemoryClientDownloadTracker(),
                manifestWriter = FileClientManifestWriter(),
                installPlanWriter = FileInstallPlanWriter(),
                destDir = dest,
                builder = builder,
                buildFleet = FixedHosts(listOf(host("h", "x86_64"))),
            )

            assertIs<DownloadClientProgramResult.Ok>(uc.invoke(spec))
            val pin = repo.find(NetworkId.OPTIMISM, EnvId.SEPOLIA, "op-node")!!
            assertEquals("1.19.9", pin.currentVersion)
            assertEquals("op-node/v1.19.9", pin.currentTag)
            assertEquals("", pin.probeError)
        }
    }
}
