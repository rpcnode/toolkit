package rpcnode.toolkit.networks.application.remove

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.repository.NetworkRepository

/** Drop a network from this install's enabled/skipped list. Returns null for a bad id. */
class RemoveNetworkUseCase(
    private val networkRepo: NetworkRepository,
)
{
    suspend operator fun invoke(network: String): NetworkId?
    {
        val id = NetworkId.parse(network) ?: return null
        networkRepo.delete(id)
        return id
    }
}
