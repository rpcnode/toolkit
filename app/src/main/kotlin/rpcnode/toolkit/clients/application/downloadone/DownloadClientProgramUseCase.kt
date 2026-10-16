package rpcnode.toolkit.clients.application.downloadone

import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.companions.ClientCompanionScripts
import rpcnode.toolkit.clients.application.companions.ClasspathClientCompanionScripts
import rpcnode.toolkit.clients.application.ArtifactDownloader
import rpcnode.toolkit.clients.application.ClientBuildRequest
import rpcnode.toolkit.clients.application.ClientBuildResult
import rpcnode.toolkit.clients.application.ClientBuilder
import rpcnode.toolkit.clients.application.build.BuildHostDirectory
import rpcnode.toolkit.clients.application.builtArtifactName
import rpcnode.toolkit.clients.application.ClientDownloadPhase
import rpcnode.toolkit.clients.application.ClientDownloadProgress
import rpcnode.toolkit.clients.application.ClientDownloadTracker
import rpcnode.toolkit.clients.application.ClientManifestFileEntry
import rpcnode.toolkit.clients.application.ClientManifestWriter
import rpcnode.toolkit.clients.application.ClientProgramKey
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.InstallPlan
import rpcnode.toolkit.clients.application.InstallPlanFile
import rpcnode.toolkit.clients.application.InstallPlanWriter
import rpcnode.toolkit.clients.application.appendVersionPlanFile
import rpcnode.toolkit.clients.application.inferArchFromFileName
import rpcnode.toolkit.clients.application.inferLaunch
import rpcnode.toolkit.clients.application.mergeInstallPlanFiles
import rpcnode.toolkit.clients.application.preferredInstallPlanProgram
import rpcnode.toolkit.clients.application.installedVersionFor
import rpcnode.toolkit.clients.application.resolveClientRelease
import rpcnode.toolkit.clients.application.version.ClientArtifactUrlResolver
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientArtifactRole
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientBuildSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.model.sameVersion
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.networks.infrastructure.filesystem.ClientDestPaths

sealed interface DownloadClientProgramResult
{
    data class Ok(val pin: ClientVersionPin) : DownloadClientProgramResult
    data class Failed(val error: String) : DownloadClientProgramResult
}

/**
 * Resolves the latest version, downloads every artifact/config **in parallel**, writes
 * `manifest.json`/`VERSION`, then persists the pin — only on success: a network/program only
 * shows up in the DB after its first successful download.
 *
 * A program with a `build` block is also compiled here (Docker, via [builder]) and the binary is published beside the
 * source, so hosts download a ready client instead of each compiling it. The pin only moves once the build succeeded;
 * without a Docker daemon the source alone is published and hosts compile as before.
 */
class DownloadClientProgramUseCase(
    private val versionRepository: ClientVersionRepository,
    private val githubReleaseClient: GitHubReleaseClient,
    private val artifactDownloader: ArtifactDownloader,
    private val tracker: ClientDownloadTracker,
    private val manifestWriter: ClientManifestWriter,
    private val installPlanWriter: InstallPlanWriter,
    private val destDir: Path,
    private val clientReleaseResolvers: Map<NetworkId, ClientReleaseResolver> = emptyMap(),
    private val artifactUrlResolvers: Map<NetworkId, ClientArtifactUrlResolver> = emptyMap(),
    private val companionScripts: ClientCompanionScripts = ClasspathClientCompanionScripts(),
    private val builder: ClientBuilder? = null,
    /** The fleet decides which CPU architectures are compiled; without it every platform of the `build` block is. */
    private val buildFleet: BuildHostDirectory? = null,
)
{
    /** One compile at a time: a Rust/C++ build already uses every core of the build machine. */
    private val buildLock = Mutex()
    private val log = org.slf4j.LoggerFactory.getLogger(DownloadClientProgramUseCase::class.java)

    /**
     * [requiredArch] (`x86_64`/`aarch64`) is the architecture of a host that is about to install this client: it is
     * built even when no other host runs it, so a node on a new architecture gets a ready binary.
     */
    suspend operator fun invoke(
        spec: ClientProgramSpec,
        force: Boolean = false,
        requiredArch: String? = null,
    ): DownloadClientProgramResult
    {
        val key = ClientProgramKey(spec.network, spec.env, spec.programId)
        tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.QUEUED))

        val installedPin = versionRepository.find(spec.network, spec.env, spec.programId)
        val installed = installedVersionFor(spec, installedPin?.currentVersion.orEmpty(), installedPin?.currentTag.orEmpty())
        val resolved = resolveClientRelease(spec, githubReleaseClient, clientReleaseResolvers, installed, installedPin?.currentTag.orEmpty())
        if (resolved.error != null)
        {
            tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.FAIL, error = resolved.error))
            return DownloadClientProgramResult.Failed(resolved.error)
        }

        val existing = versionRepository.find(spec.network, spec.env, spec.programId)
        val networkSeg = ClientDestPaths.safeSegment(spec.network.value)
        val envSeg = ClientDestPaths.safeSegment(spec.env.value)
        if (networkSeg == null || envSeg == null)
        {
            val error = "invalid network/env path segment"
            tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.FAIL, error = error))
            return DownloadClientProgramResult.Failed(error)
        }
        val dir = destDir.resolve(networkSeg).resolve(envSeg)

        val sameAsPinned = existing != null && existing.currentVersion.isNotBlank() &&
            sameVersion(existing.currentVersion, resolved.version)
        val buildPlatforms = spec.build?.let { platformsToBuild(it, requiredArch) }.orEmpty()
        if (!force && existing != null && existing.probeError.isBlank() && sameAsPinned &&
            (spec.build == null || builtFilesPresent(dir, spec.build, buildPlatforms))
        ) {
            // Keep companion scripts (e.g. Solana run-validator.sh.tmpl) fresh even when
            // the tarball pin is unchanged — agent sync needs them under clients/<env>/.
            companionScripts.ship(spec.network, dir)
            rewriteInstallPlan(dir, spec)
            tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.DONE))
            return DownloadClientProgramResult.Ok(existing)
        }

        var builtStage: Path? = null
        return try
        {
            // Build first, into a scratch dir: the served dir keeps the previous release (source + binary) until the
            // new binary exists, so a host syncing mid-build never gets a new source with an old binary.
            val built = spec.build?.let { build ->
                when (val result = runBuild(spec, build, buildPlatforms, resolved.version, resolved.tag, dir, key))
                {
                    is BuildStep.Failed ->
                    {
                        tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.FAIL, error = result.error))
                        return DownloadClientProgramResult.Failed(result.error)
                    }
                    is BuildStep.Done -> result.also { builtStage = it.stage }
                }
            }
            // A new release drops every old binary (hosts prefer a prebuilt file, a stale one would run the old
            // version); the same release keeps the other architectures' binaries and only adds the new one.
            if (!sameAsPinned)
            {
                spec.build?.let { removeBuiltFiles(dir, it) }
            }

            tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.DOWNLOAD, name = spec.programId))
            val downloadedFiles = coroutineScope {
                (spec.artifacts + spec.configs).map { artifact ->
                    async(Dispatchers.IO) {
                        val aarch64 = isAarch64Host()
                        val url = resolveUrl(spec, artifact, resolved.version, resolved.tag, aarch64)
                        val saveName = resolveSaveName(artifact, aarch64)
                        val dest = dir.resolve(saveName)
                        artifactDownloader.download(url, dest) { bytes, total ->
                            tracker.set(
                                key,
                                ClientDownloadProgress(ClientDownloadPhase.DOWNLOAD, name = saveName, bytes = bytes, total = total),
                            )
                        }
                        ClientManifestFileEntry(
                            name = saveName,
                            role = if (artifact.role == ClientArtifactRole.ARTIFACT) "artifact" else "config",
                            url = url,
                        )
                    }
                }.awaitAll()
            } + publishBuilt(built, dir, resolved.tag)

            companionScripts.ship(spec.network, dir)

            manifestWriter.write(
                dir = dir,
                network = spec.network.value,
                env = spec.env.value,
                program = spec.programId,
                version = resolved.version,
                tag = resolved.tag,
                source = resolved.sourceLabel,
                notes = existing?.notes.orEmpty(),
                files = downloadedFiles,
            )
            val planFiles = mergeInstallPlanFiles(
                dir = dir,
                downloaded = appendVersionPlanFile(
                    downloadedFiles.map { f ->
                        InstallPlanFile(
                            role = f.role,
                            path = f.name,
                            arch = inferArchFromFileName(f.name),
                        )
                    },
                ),
            )
            val planProgram = preferredInstallPlanProgram(spec.programId, planFiles)
            installPlanWriter.write(
                dir,
                InstallPlan(
                    network = spec.network.value,
                    env = spec.env.value,
                    program = planProgram,
                    files = planFiles,
                    launch = inferLaunch(planProgram, planFiles, spec.requirements.javaMajor),
                ),
            )

            val now = Instant.now().toString()
            val pin = ClientVersionPin(
                network = spec.network,
                env = spec.env,
                program = spec.programId,
                currentVersion = resolved.version,
                currentTag = resolved.tag,
                latestVersion = resolved.version,
                latestTag = resolved.tag,
                source = resolved.sourceLabel,
                url = downloadedFiles.firstOrNull { it.role == "artifact" }?.url.orEmpty(),
                notes = existing?.notes.orEmpty(),
                skipReason = spec.skipReason.orEmpty(),
                probeError = "",
                probedAt = now,
                updatedAt = now,
            )
            versionRepository.applySynced(pin)
            tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.DONE))
            DownloadClientProgramResult.Ok(pin)
        }
        catch (e: CancellationException)
        {
            throw e
        }
        catch (e: Exception)
        {
            val error = e.message ?: "download failed"
            tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.FAIL, error = error))
            DownloadClientProgramResult.Failed(error)
        }
        finally
        {
            builtStage?.toFile()?.deleteRecursively()
        }
    }

    /** True when the pinned release is the one being built, so a forced re-download keeps the binaries it already has. */
    private suspend fun existingIsSame(spec: ClientProgramSpec, version: String): Boolean =
        versionRepository.find(spec.network, spec.env, spec.programId)
            ?.let { it.currentVersion.isNotBlank() && sameVersion(it.currentVersion, version) } == true

    private fun builtFilesPresent(dir: Path, build: ClientBuildSpec, platforms: List<String>): Boolean =
        platforms.all { platform ->
            build.outputs.all { output ->
                java.nio.file.Files.isRegularFile(dir.resolve(builtArtifactName(output, ClientBuildSpec.archOf(platform))))
            }
        }

    private sealed interface BuildStep
    {
        /** [stage] holds the freshly built files; null when there was nothing to build (no Docker). */
        data class Done(val stage: Path?, val names: List<String>) : BuildStep
        data class Failed(val error: String) : BuildStep
    }

    /** Platforms of [build] that the fleet runs (plus [requiredArch]); all of them when no fleet is wired. */
    private suspend fun platformsToBuild(build: ClientBuildSpec, requiredArch: String?): List<String>
    {
        val fleet = buildFleet ?: return build.platforms
        val wanted = fleet.hosts().map { it.arch }.toMutableSet()
        ClientBuildSpec.normalizeArch(requiredArch)?.let { wanted += it }
        return build.platforms.filter { ClientBuildSpec.archOf(it) in wanted }
    }

    private suspend fun runBuild(
        spec: ClientProgramSpec,
        build: ClientBuildSpec,
        platforms: List<String>,
        version: String,
        tag: String,
        dir: Path,
        key: ClientProgramKey,
    ): BuildStep
    {
        val active = builder
        if (platforms.isEmpty() || (existingIsSame(spec, version) && builtFilesPresent(dir, build, platforms)))
        {
            return BuildStep.Done(stage = null, names = emptyList())
        }
        if (active == null)
        {
            log.warn("{}/{} has a build block but no builder is wired — publishing the source only", spec.network.value, spec.programId)
            return BuildStep.Done(stage = null, names = emptyList())
        }
        val label = "building ${build.outputs.joinToString(", ")} in Docker"
        tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.BUILD, name = "queued: $label"))
        return buildLock.withLock {
            tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.BUILD, name = label))
            val stage = java.nio.file.Files.createTempDirectory("rpcnode-client-build-")
            var lastShown = 0L
            val result = active.build(ClientBuildRequest(spec, version, tag, stage, platforms)) { line ->
                val now = System.currentTimeMillis()
                if (line.isNotBlank() && now - lastShown > 2_000)
                {
                    lastShown = now
                    tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.BUILD, name = "$label · ${line.trim().take(90)}"))
                }
            }
            when (result)
            {
                is ClientBuildResult.Built -> BuildStep.Done(stage, result.names)
                is ClientBuildResult.Unavailable ->
                {
                    stage.toFile().deleteRecursively()
                    log.warn("{}/{}: cannot build here ({}) — publishing the source only, hosts compile it", spec.network.value, spec.programId, result.reason)
                    BuildStep.Done(stage = null, names = emptyList())
                }
                is ClientBuildResult.Failed ->
                {
                    stage.toFile().deleteRecursively()
                    BuildStep.Failed("build of ${spec.programId} $tag failed: ${result.error}")
                }
            }
        }
    }

    /** Previous release's binaries must go: hosts prefer a prebuilt file, so a stale one would run the old version. */
    private fun removeBuiltFiles(dir: Path, build: ClientBuildSpec)
    {
        for (platform in build.platforms.plus(ClientBuildSpec.SUPPORTED_PLATFORMS).distinct())
        {
            for (output in build.outputs)
            {
                runCatching {
                    java.nio.file.Files.deleteIfExists(dir.resolve(builtArtifactName(output, ClientBuildSpec.archOf(platform))))
                }
            }
        }
    }

    private fun publishBuilt(built: BuildStep.Done?, dir: Path, tag: String): List<ClientManifestFileEntry>
    {
        val stage = built?.stage ?: return emptyList()
        try
        {
            java.nio.file.Files.createDirectories(dir)
            return built.names.map { name ->
                java.nio.file.Files.move(stage.resolve(name), dir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                ClientManifestFileEntry(name = name, role = "artifact", url = "docker-build:$tag")
            }
        }
        finally
        {
            stage.toFile().deleteRecursively()
        }
    }

    private suspend fun rewriteInstallPlan(dir: Path, spec: ClientProgramSpec)
    {
        if (!java.nio.file.Files.isDirectory(dir))
        {
            return
        }
        val planFiles = mergeInstallPlanFiles(dir, emptyList())
        if (planFiles.isEmpty())
        {
            return
        }
        val planProgram = preferredInstallPlanProgram(spec.programId, planFiles)
        installPlanWriter.write(
            dir,
            InstallPlan(
                network = spec.network.value,
                env = spec.env.value,
                program = planProgram,
                files = planFiles,
                launch = inferLaunch(planProgram, planFiles, spec.requirements.javaMajor),
            ),
        )
    }

    private suspend fun resolveUrl(
        spec: ClientProgramSpec,
        artifact: ClientArtifactSpec,
        version: String,
        tag: String,
        aarch64: Boolean,
    ): String
    {
        val override = artifactUrlResolvers[spec.network]?.resolve(spec, artifact, version, tag, aarch64)
        if (!override.isNullOrBlank())
        {
            return override
        }
        val template = if (aarch64 && artifact.urlTemplateAarch64 != null) artifact.urlTemplateAarch64 else artifact.urlTemplate
        return template.replace("{version}", version).replace("{tag}", tag)
    }

    private fun resolveSaveName(artifact: ClientArtifactSpec, aarch64: Boolean): String
    {
        if (aarch64 && !artifact.nameAarch64.isNullOrBlank())
        {
            return artifact.nameAarch64
        }
        return artifact.name
    }

    private fun isAarch64Host(): Boolean
    {
        val arch = System.getProperty("os.arch")?.lowercase().orEmpty()
        return arch.contains("aarch64") || arch.contains("arm64")
    }
}
