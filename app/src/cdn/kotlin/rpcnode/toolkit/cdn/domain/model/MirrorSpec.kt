package rpcnode.toolkit.cdn.domain.model

import rpcnode.toolkit.cdn.application.sync.SnapshotTarget

/** One official upstream mirror recipe shipped with the CDN JAR. */
data class MirrorSpec(
    val network: String,
    val env: String,
    val type: String,
    val mirror: String,
    val filename: String,
    val discover: String,
)
{
    fun toTarget(): SnapshotTarget = SnapshotTarget(network, env, type)

    val id: String get() = "$network/$env/$type"
}
