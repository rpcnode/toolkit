package rpcnode.toolkit.chains.evmfamily.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotRef
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotSentinel
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.domain.model.SnapshotArchive

/**
 * Snapshots restored by a script on the host (client CLI `download`, genesis import): the resolver only names the
 * restore. `kindsByEnv` lists the snapshot type ids each env accepts; an unknown type or env resolves to nothing.
 */
class EvmSnapshotSentinelResolver(
    private val network: String,
    private val kindsByEnv: Map<String, Set<String>>,
    private val defaultKind: String,
) : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        val kind = typeId.trim().lowercase().ifEmpty { defaultKind }
        if (kind !in kindsByEnv[env.value].orEmpty()) return null
        return SnapshotArchive(
            url = EvmSnapshotSentinel.url(EvmSnapshotRef(network, env.value, kind)),
            streamUnpack = false,
            sizeBytes = null,
        )
    }
}
