package rpcnode.toolkit.networks.domain.repository

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.SnapshotMirrorSpec

interface SnapshotMirrorCatalog
{
    fun mirror(network: NetworkId, env: EnvId, typeId: String): SnapshotMirrorSpec?

    fun typesFor(network: NetworkId, env: EnvId): List<SnapshotMirrorSpec>
}
