package rpcnode.toolkit.clients

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository

class FakeClientVersionRepository(
    seed: List<ClientVersionPin> = emptyList(),
) : ClientVersionRepository
{
    private val byKey = seed.associateByTo(mutableMapOf()) { Triple(it.network, it.env, it.program) }
    private val purged = mutableSetOf<NetworkId>()

    override suspend fun list(): List<ClientVersionPin> = byKey.values.toList()

    override suspend fun find(network: NetworkId, env: EnvId, program: String): ClientVersionPin? =
        byKey[Triple(network, env, program)]

    override suspend fun applyProbe(pin: ClientVersionPin)
    {
        val key = Triple(pin.network, pin.env, pin.program)
        if (byKey.containsKey(key))
        {
            byKey[key] = pin
        }
    }

    override suspend fun applySynced(pin: ClientVersionPin)
    {
        byKey[Triple(pin.network, pin.env, pin.program)] = pin
    }

    override suspend fun deleteEnv(network: NetworkId, env: EnvId)
    {
        byKey.keys.filter { it.first == network && it.second == env }.forEach { byKey.remove(it) }
    }

    override suspend fun deleteNetwork(network: NetworkId)
    {
        byKey.keys.filter { it.first == network }.forEach { byKey.remove(it) }
    }

    override suspend fun isPurged(network: NetworkId): Boolean = network in purged

    override suspend fun markPurged(network: NetworkId)
    {
        purged += network
    }

    override suspend fun clearPurged(network: NetworkId)
    {
        purged -= network
    }
}
