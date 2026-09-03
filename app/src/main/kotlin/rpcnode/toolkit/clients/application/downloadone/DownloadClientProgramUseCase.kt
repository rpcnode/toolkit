package rpcnode.toolkit.clients.application.downloadone

import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.companions.ClientCompanionScripts
import rpcnode.toolkit.clients.application.companions.ClasspathClientCompanionScripts
import rpcnode.toolkit.clients.application.ArtifactDownloader
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
import rpcnode.toolkit.clients.application.resolveClientRelease
import rpcnode.toolkit.clients.application.version.ClientArtifactUrlResolver
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientArtifactRole
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
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
)
{
    suspend operator fun invoke(spec: ClientProgramSpec, force: Boolean = false): DownloadClientProgramResult
    {
        val key = ClientProgramKey(spec.network, spec.env, spec.programId)
        tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.QUEUED))

        val resolved = resolveClientRelease(spec, githubReleaseClient, clientReleaseResolvers)
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

        if (!force && existing != null && existing.probeError.isBlank() && existing.currentVersion.isNotBlank() &&
            sameVersion(existing.currentVersion, resolved.version)
        ) {
            // Keep companion scripts (e.g. Solana run-validator.sh.tmpl) fresh even when
            // the tarball pin is unchanged — agent sync needs them under clients/<env>/.
            companionScripts.ship(spec.network, dir)
            rewriteInstallPlan(dir, spec)
            tracker.set(key, ClientDownloadProgress(ClientDownloadPhase.DONE))
            return DownloadClientProgramResult.Ok(existing)
        }

        return try
        {
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
            }

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
                    launch = inferLaunch(planProgram, planFiles),
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
                launch = inferLaunch(planProgram, planFiles),
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
