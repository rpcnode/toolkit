package rpcnode.toolkit.clients.application.preview

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.ClientDownloadTracker
import rpcnode.toolkit.clients.application.ClientPreviewStore
import rpcnode.toolkit.clients.application.ClientProgramKey
import rpcnode.toolkit.clients.application.ClientRowView
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.model.sameVersion
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository

data class PreviewClientsResult(val rows: List<ClientRowView>, val want: Int)

/** Live status for the Add/Update-client modal: probe result (or persisted pin) + in-flight download progress. */
class PreviewClientsUseCase(
    private val versionRepository: ClientVersionRepository,
    private val programCatalog: ClientProgramCatalog,
    private val previewStore: ClientPreviewStore,
    private val tracker: ClientDownloadTracker,
)
{
    suspend operator fun invoke(network: NetworkId, env: EnvId): PreviewClientsResult
    {
        var programIds = programCatalog.programsFor(network, env).map { it.programId }
        if (programIds.isEmpty())
        {
            programIds = listOf(network.value)
        }
        val rows = programIds.mapNotNull { programId -> buildRow(network, env, programId) }
        return PreviewClientsResult(rows = rows, want = programIds.size)
    }

    private suspend fun buildRow(network: NetworkId, env: EnvId, programId: String): ClientRowView?
    {
        val key = ClientProgramKey(network, env, programId)
        val previewRow = previewStore.get(key)
        val dbRow = versionRepository.find(network, env, programId)
        val progress = tracker.get(key)
        if (previewRow == null && dbRow == null && progress == null)
        {
            return null
        }

        var pin = previewRow ?: ClientVersionPin(network = network, env = env, program = programId)
        if (dbRow != null && dbRow.currentVersion.isNotBlank())
        {
            pin = pin.copy(
                currentVersion = dbRow.currentVersion,
                currentTag = dbRow.currentTag,
                latestVersion = pin.latestVersion.ifBlank { dbRow.latestVersion },
                latestTag = pin.latestTag.ifBlank { dbRow.latestTag },
                source = pin.source.ifBlank { dbRow.source },
                skipReason = pin.skipReason.ifBlank { dbRow.skipReason },
                probeError = pin.probeError.ifBlank { dbRow.probeError },
            )
        }

        return when
        {
            progress != null -> ClientRowView(
                pin = pin,
                downloadPhase = progress.phase.name.lowercase(),
                downloadName = progress.name.ifBlank { null },
                downloadBytes = progress.bytes,
                downloadTotal = progress.total,
                downloadPct = if (progress.total > 0) progress.bytes * 100.0 / progress.total else 0.0,
                downloadError = progress.error.ifBlank { null },
            )
            // Only mark done when the pin matches latest — stale pins must not look finished
            // (Update modal would flicker 100% and never complete).
            pin.currentVersion.isNotBlank() &&
                (pin.latestVersion.isBlank() || sameVersion(pin.currentVersion, pin.latestVersion)) ->
                ClientRowView(pin = pin, downloadPhase = "done", downloadPct = 100.0)
            else -> ClientRowView(pin = pin)
        }
    }
}
