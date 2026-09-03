package rpcnode.toolkit.clients.application.probe

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.GitHubTokenProvider
import rpcnode.toolkit.clients.application.probeone.ProbeClientProgramUseCase
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository

sealed interface ProbeClientsResult
{
    data object Done : ProbeClientsResult
    data object TokenRequired : ProbeClientsResult
}

/**
 * "Check latest" — one request walks every already-added client in the DB, probes latest
 * in parallel, and writes `latest_*` on those rows. The handler returns after all probes
 * finish so the panel can reload and show Update on stale pins.
 */
class ProbeClientsUseCase(
    private val versionRepository: ClientVersionRepository,
    private val programCatalog: ClientProgramCatalog,
    private val probeOne: ProbeClientProgramUseCase,
    private val tokenProvider: GitHubTokenProvider,
    private val concurrency: Semaphore,
)
{
    suspend operator fun invoke(network: String? = null, env: String? = null, program: String? = null): ProbeClientsResult
    {
        if (tokenProvider.current().isNullOrBlank())
        {
            return ProbeClientsResult.TokenRequired
        }

        val targets = addedSpecs(network, env, program)
        coroutineScope {
            targets.map { spec ->
                async { concurrency.withPermit { probeOne(spec) } }
            }.awaitAll()
        }
        return ProbeClientsResult.Done
    }

    private suspend fun addedSpecs(network: String?, env: String?, program: String?): List<ClientProgramSpec>
    {
        val networkId = network?.trim()?.lowercase()?.ifEmpty { null }?.let { NetworkId.parse(it) }
        val envId = env?.trim()?.ifEmpty { null }?.let { EnvId.parse(it) }
        val programRaw = program?.trim()?.lowercase()?.ifEmpty { null }
        return versionRepository.list()
            .filter { it.currentVersion.isNotBlank() }
            .filter { networkId == null || it.network == networkId }
            .filter { envId == null || it.env == envId }
            .filter { programRaw == null || it.program.lowercase() == programRaw }
            .filter { !versionRepository.isPurged(it.network) }
            .mapNotNull { pin ->
                programCatalog.programsFor(pin.network, pin.env).firstOrNull { it.programId == pin.program }
            }
    }
}
