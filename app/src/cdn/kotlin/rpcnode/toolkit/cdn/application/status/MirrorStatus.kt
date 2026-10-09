package rpcnode.toolkit.cdn.application.status

import rpcnode.toolkit.cdn.application.sync.SnapshotTarget

enum class MirrorState
{
    EMPTY,
    READY,
    DOWNLOADING,
}

data class MirrorStatusRow(
    val target: SnapshotTarget,
    val state: MirrorState,
    /** Completed VERSION on disk, if any. */
    val onDiskVersion: String?,
    /** Version currently being fetched (from progress file), if any. */
    val fetchingVersion: String?,
    val filename: String?,
    val haveBytes: Long?,
    val totalBytes: Long?,
    /** Public download hits (from downloads.json), if any. */
    val downloadCount: Long = 0L,
)
{
    val id: String get() = target.id

    val displayVersion: String
        get() = when (state)
        {
            MirrorState.DOWNLOADING ->
                fetchingVersion ?: onDiskVersion ?: "—"
            MirrorState.READY -> onDiskVersion ?: "—"
            MirrorState.EMPTY -> "—"
        }

    val progressPct: Int?
        get()
        {
            val have = haveBytes ?: return null
            val total = totalBytes ?: return null
            if (total <= 0L)
            {
                return null
            }
            return ((have * 100) / total).toInt().coerceIn(0, 100)
        }
}

fun interface MirrorStatusReader
{
    fun status(targets: List<SnapshotTarget>): List<MirrorStatusRow>
}
