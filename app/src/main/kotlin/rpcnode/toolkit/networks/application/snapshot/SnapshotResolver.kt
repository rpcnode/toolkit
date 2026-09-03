package rpcnode.toolkit.networks.application.snapshot

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.networks.domain.model.SnapshotArchive

/**
 * One network's live snapshot archive for an env + type (full/lite/archive/…).
 * Implementation lives in `chains/<id>/infrastructure`. Empty [typeId] → network default.
 */
fun interface SnapshotResolver
{
    suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
}
