package rpcnode.toolkit.clients.domain.repository

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientVersionPin

interface ClientVersionRepository
{
    suspend fun list(): List<ClientVersionPin>

    suspend fun find(network: NetworkId, env: EnvId, program: String): ClientVersionPin?

    /** Updates `latest_*`/`probe_error`/`probed_at` on an existing pin. Never creates a row. */
    suspend fun applyProbe(pin: ClientVersionPin)

    /** Upserts after a successful download — the row can appear here for the first time. */
    suspend fun applySynced(pin: ClientVersionPin)

    suspend fun deleteEnv(network: NetworkId, env: EnvId)

    suspend fun deleteNetwork(network: NetworkId)

    suspend fun isPurged(network: NetworkId): Boolean

    suspend fun markPurged(network: NetworkId)

    suspend fun clearPurged(network: NetworkId)
}
