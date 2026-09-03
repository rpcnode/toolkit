package rpcnode.toolkit.clients.application.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.ClientDownloadPhase
import rpcnode.toolkit.clients.application.ClientDownloadProgress
import rpcnode.toolkit.clients.application.ClientDownloadTracker
import rpcnode.toolkit.clients.application.ClientProgramKey
import rpcnode.toolkit.clients.application.GitHubTokenProvider
import rpcnode.toolkit.clients.application.downloadone.DownloadClientProgramUseCase
import rpcnode.toolkit.clients.application.resolveClientTargets
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.networks.domain.model.NetworkPinOnly

sealed interface SyncClientsResult
{
    data object Started : SyncClientsResult
    data object TokenRequired : SyncClientsResult
}

/** "Update" / "Download" — fires one background [DownloadClientProgramUseCase] per matched program. */
class SyncClientsUseCase(
    private val versionRepository: ClientVersionRepository,
    private val programCatalog: ClientProgramCatalog,
    private val downloadOne: DownloadClientProgramUseCase,
    private val tracker: ClientDownloadTracker,
    private val tokenProvider: GitHubTokenProvider,
    private val backgroundScope: CoroutineScope,
    private val concurrency: Semaphore,
)
{
    suspend operator fun invoke(
        network: String? = null,
        env: String? = null,
        program: String? = null,
        force: Boolean = false,
    ): SyncClientsResult
    {
        val networkRaw = network?.trim()?.lowercase()?.ifEmpty { null }
        if (networkRaw != null)
        {
            val networkId = NetworkId.parse(networkRaw)
            if (networkId != null && versionRepository.isPurged(networkId))
            {
                if (!force)
                {
                    return SyncClientsResult.Started
                }
                versionRepository.clearPurged(networkId)
            }
        }

        val targets = resolveClientTargets(versionRepository, programCatalog, network, env, program)
        val pinOnlyOnly = targets.isNotEmpty() && targets.all { NetworkPinOnly.isPinOnly(it.network) }
        if (tokenProvider.current().isNullOrBlank() && !pinOnlyOnly)
        {
            return SyncClientsResult.TokenRequired
        }

        for (spec in targets)
        {
            // Written up front so the Add-client modal shows a progress bar before the download starts.
            tracker.set(
                ClientProgramKey(spec.network, spec.env, spec.programId),
                ClientDownloadProgress(ClientDownloadPhase.QUEUED),
            )
            backgroundScope.launch { concurrency.withPermit { downloadOne(spec, force) } }
        }
        return SyncClientsResult.Started
    }
}
