package rpcnode.toolkit.cdn.application.targets

import rpcnode.toolkit.cdn.application.sync.SnapshotTarget

interface SnapshotTargetStore
{
    fun list(): List<SnapshotTarget>
    fun add(target: SnapshotTarget)
    fun remove(id: String): Boolean
}
