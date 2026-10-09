package rpcnode.toolkit.clients.application.add

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.GitHubTokenProvider
import rpcnode.toolkit.clients.application.probeone.ProbeClientProgramUseCase
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.model.ClientVersionSource
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.networks.domain.model.NetworkPinOnly

sealed interface AddClientResult
{
    data class Ok(val network: NetworkId, val env: EnvId, val probeQueued: Boolean) : AddClientResult
    data object UnknownNetwork : AddClientResult
    data object UnknownEnv : AddClientResult
}

/**
 * Registers intent to add a network/env.
 *
 * Normal networks: the pin is written by `ApplySynced` after the first successful download.
 * Pin-only networks (no CDN artifacts): write the pin here so Clients / Add node list them
 * without a GitHub download.
 */
class AddClientUseCase(
    private val catalog: NetworkCatalog,
    private val versionRepository: ClientVersionRepository,
    private val programCatalog: ClientProgramCatalog,
    private val probeOne: ProbeClientProgramUseCase,
    private val tokenProvider: GitHubTokenProvider,
    private val backgroundScope: CoroutineScope,
    private val concurrency: Semaphore,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(networkRaw: String, envRaw: String): AddClientResult
    {
        val networkId = NetworkId.parse(networkRaw) ?: return AddClientResult.UnknownNetwork
        val chain = catalog.find(networkId) ?: return AddClientResult.UnknownNetwork
        val envId = chain.env(envRaw)?.id ?: return AddClientResult.UnknownEnv

        versionRepository.clearPurged(networkId)

        if (NetworkPinOnly.isPinOnly(networkId))
        {
            val now = clock()
            for (spec in programCatalog.programsFor(networkId, envId))
            {
                val pinned = spec.source as? ClientVersionSource.Pinned
                val version = pinned?.version?.trim().orEmpty().ifEmpty { "pin" }
                val tag = pinned?.tag?.trim().orEmpty().ifEmpty { version }
                versionRepository.applySynced(
                    ClientVersionPin(
                        network = networkId,
                        env = envId,
                        program = spec.programId,
                        currentVersion = version,
                        currentTag = tag,
                        latestVersion = version,
                        latestTag = tag,
                        source = pinned?.label?.trim().orEmpty().ifEmpty { "pin-only" },
                        skipReason = spec.skipReason?.trim().orEmpty()
                            .ifEmpty { "Host install (pin-only)" },
                        probeError = "",
                        probedAt = now,
                        updatedAt = now,
                    ),
                )
            }
            return AddClientResult.Ok(network = networkId, env = envId, probeQueued = false)
        }

        val hasToken = !tokenProvider.current().isNullOrBlank()
        if (hasToken)
        {
            for (spec in programCatalog.programsFor(networkId, envId))
            {
                backgroundScope.launch { concurrency.withPermit { probeOne(spec) } }
            }
        }
        return AddClientResult.Ok(network = networkId, env = envId, probeQueued = hasToken)
    }
}
