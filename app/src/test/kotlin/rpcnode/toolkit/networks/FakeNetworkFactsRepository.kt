package rpcnode.toolkit.networks

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.NetworkFacts
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository

class FakeNetworkFactsRepository(
    private val byNetwork: Map<NetworkId, NetworkFacts> = emptyMap(),
) : NetworkFactsRepository
{
    override fun factsFor(network: NetworkId): NetworkFacts? = byNetwork[network]
}
