package rpcnode.toolkit.clients.application.probeone

import java.time.Instant
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.ClientPreviewStore
import rpcnode.toolkit.clients.application.ClientProgramKey
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.resolveClientRelease
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository

/**
 * Refreshes `latest_*` for one program. Never creates a DB row — an unsynced program's probe
 * result lives only in [ClientPreviewStore] until the first successful download (see
 * `DownloadClientProgramUseCase`).
 */
class ProbeClientProgramUseCase(
    private val versionRepository: ClientVersionRepository,
    private val githubReleaseClient: GitHubReleaseClient,
    private val previewStore: ClientPreviewStore,
    private val clientReleaseResolvers: Map<NetworkId, ClientReleaseResolver> = emptyMap(),
)
{
    suspend operator fun invoke(spec: ClientProgramSpec): ClientVersionPin
    {
        val resolved = resolveClientRelease(spec, githubReleaseClient, clientReleaseResolvers)
        val now = Instant.now().toString()
        val key = ClientProgramKey(spec.network, spec.env, spec.programId)

        val existing = versionRepository.find(spec.network, spec.env, spec.programId)
        val base = existing ?: previewStore.get(key) ?: ClientVersionPin(
            network = spec.network,
            env = spec.env,
            program = spec.programId,
        )
        val pin = base.copy(
            latestVersion = resolved.version,
            latestTag = resolved.tag,
            source = resolved.sourceLabel,
            skipReason = spec.skipReason.orEmpty(),
            probeError = resolved.error.orEmpty(),
            probedAt = now,
            updatedAt = now,
        )

        if (existing != null)
        {
            versionRepository.applyProbe(pin)
        }
        else
        {
            previewStore.put(key, pin)
        }
        return pin
    }
}
