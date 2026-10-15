package rpcnode.toolkit.networks.domain.repository

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.NetworkFacts

/** Static reference facts for one network, or null when this install ships none for it. */
interface NetworkFactsRepository
{
    fun factsFor(network: NetworkId): NetworkFacts?
}

/** The env's `tipLagBlocks` from network.yml, or null when it does not set one. */
fun NetworkFactsRepository.tipLagFor(network: NetworkId, env: EnvId): Long? =
    factsFor(network)?.envs?.firstOrNull { it.id == env.value }?.tipLagBlocks
