package rpcnode.toolkit.agent.domain.model

data class SnapshotJob(
    val jobId: String,
    val url: String,
    val destDir: String,
    val streamUnpack: Boolean = false,
    val sizeBytes: Long? = null,
    val pct: Double = 0.0,
    val phase: String = "idle",
    val detail: String = "",
    val ready: Boolean = false,
    val failed: Boolean = false,
    val error: String = "",
    val running: Boolean = false,
    /** Recent operator-facing lines for the install wizard (newest last). */
    val logTail: List<String> = emptyList(),
)
