package rpcnode.toolkit.networks.domain.repository

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.NetworkFacts

/** Static reference facts for one network, or null when this install ships none for it. */
interface NetworkFactsRepository
{
    fun factsFor(network: NetworkId): NetworkFacts?
}
