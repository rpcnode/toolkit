package rpcnode.toolkit.networks.application.list

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.application.ClientFilesReadyChecker
import rpcnode.toolkit.networks.domain.model.NetworkFacts
import rpcnode.toolkit.networks.domain.model.NetworkPinOnly
import rpcnode.toolkit.networks.domain.model.NetworkStatus
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.networks.domain.repository.NetworkRepository

data class NetworkListItem(
    val id: NetworkId,
    val label: String,
    val envs: List<EnvId>,
    /** true only when the operator turned it on ([NetworkStatus.READY]). */
    val enabled: Boolean,
    /** null when the network was never added on this install. */
    val status: NetworkStatus?,
    val filesReady: Boolean,
    val pinOnly: Boolean,
    /** Disk/host/snapshot reference facts for this network, or null when this install ships none. */
    val facts: NetworkFacts?,
)

/**
 * List networks from the yaml mapping (`chains/<id>/network.yml`), joined with this install's
 * enabled/skipped state and with [NetworkFactsRepository] reference facts (disk plan, host
 * sizing — all loaded once, then substituted in by id; see
 * [rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository]).
 * `all = false` returns only networks the operator turned on ([NetworkStatus.READY]).
 * Live snapshot archives are not resolved here — callers that need one env's archive use
 * [rpcnode.toolkit.networks.application.snapshot.ResolveSnapshotUseCase].
 */
class ListNetworksUseCase(
    private val catalog: NetworkCatalog,
    private val networkRepo: NetworkRepository,
    private val filesReady: ClientFilesReadyChecker,
    private val facts: NetworkFactsRepository,
)
{
    suspend operator fun invoke(all: Boolean): List<NetworkListItem>
    {
        val enabledByNetwork = networkRepo.list().associateBy { it.network }
        val items = mutableListOf<NetworkListItem>()

        for (chain in catalog.all())
        {
            val enabled = enabledByNetwork[chain.id]
            val status = enabled?.status
            val on = status == NetworkStatus.READY
            if (!all && !on)
            {
                continue
            }
            val pinOnly = NetworkPinOnly.isPinOnly(chain.id)
            val ready = pinOnly || filesReady.ready(chain.id, chain.envs.map { it.id })
            items += NetworkListItem(
                id = chain.id,
                label = chain.displayLabel(),
                envs = chain.envs.map { it.id },
                enabled = on,
                status = status,
                filesReady = ready,
                pinOnly = pinOnly,
                facts = facts.factsFor(chain.id),
            )
        }

        return items
    }
}
