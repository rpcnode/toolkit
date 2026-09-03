package rpcnode.toolkit.networks

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.Network
import rpcnode.toolkit.networks.domain.model.NetworkStatus
import rpcnode.toolkit.networks.domain.repository.NetworkRepository

class FakeNetworkRepository(
    seed: List<Network> = emptyList(),
) : NetworkRepository
{
    private val byNetwork = seed.associateByTo(mutableMapOf()) { it.network }

    override suspend fun list(): List<Network> = byNetwork.values.toList()

    override suspend fun upsert(network: NetworkId, status: NetworkStatus, notes: String)
    {
        byNetwork[network] = Network(network = network, status = status, addedAt = "now", notes = notes)
    }

    override suspend fun delete(network: NetworkId)
    {
        byNetwork.remove(network)
    }
}
