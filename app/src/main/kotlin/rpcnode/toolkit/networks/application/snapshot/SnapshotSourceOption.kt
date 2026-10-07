package rpcnode.toolkit.networks.application.snapshot

/** One snapshot download origin the operator can pick (official mirror or Snapshot CDN). */
data class SnapshotSourceOption(
    val id: String,
    val label: String,
    val url: String?,
    val version: String?,
    val sizeBytes: Long?,
    val streamUnpack: Boolean?,
    val available: Boolean,
    val detail: String?,
)

sealed interface SnapshotSourcesResult
{
    data class Resolved(
        val typeId: String,
        val officialUrl: String?,
        val officialVersion: String?,
        val sources: List<SnapshotSourceOption>,
        /** Suggested pick when the operator has not chosen yet. */
        val defaultSourceId: String?,
    ) : SnapshotSourcesResult

    data object UnknownNetwork : SnapshotSourcesResult
    data object UnknownEnv : SnapshotSourcesResult
}
