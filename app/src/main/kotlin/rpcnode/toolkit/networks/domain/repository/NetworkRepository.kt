package rpcnode.toolkit.networks.domain.repository

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.Network
import rpcnode.toolkit.networks.domain.model.NetworkStatus

interface NetworkRepository
{
    suspend fun list(): List<Network>
    suspend fun upsert(network: NetworkId, status: NetworkStatus, notes: String)
    suspend fun delete(network: NetworkId)
}
