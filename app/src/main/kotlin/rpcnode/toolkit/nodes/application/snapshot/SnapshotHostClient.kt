package rpcnode.toolkit.nodes.application.snapshot

data class SnapshotHostStartCommand(
    val jobId: String,
    val url: String,
    val destDir: String,
    val streamUnpack: Boolean,
    val sizeBytes: Long?,
)

data class SnapshotHostProgress(
    val ok: Boolean = true,
    val pct: Double? = null,
    val phase: String = "",
    val detail: String = "",
    val ready: Boolean = false,
    val failed: Boolean = false,
    val error: String = "",
    val logTail: List<String> = emptyList(),
)

fun interface StartSnapshotOnHost
{
    /** POST /api/v1/snapshot/start on the host agent. Null = agent unreachable. */
    suspend fun start(agentUrl: String, token: String, command: SnapshotHostStartCommand): Boolean?
}

fun interface PollSnapshotOnHost
{
    /** GET /api/v1/snapshot/progress on the host agent. Null = agent unreachable. */
    suspend fun progress(agentUrl: String, token: String, jobId: String): SnapshotHostProgress?
}

fun interface StopSnapshotOnHost
{
    /** POST /api/v1/snapshot/stop — null = unreachable. True = stopped (or idle). */
    suspend fun stop(agentUrl: String, token: String, jobId: String, wipeDest: Boolean): Boolean?
}

data class SnapshotHostSpeedSample(
    val id: String,
    val url: String,
)

data class SnapshotHostSpeedResult(
    val id: String,
    val available: Boolean,
    val bytesPerSec: Long? = null,
    val sampleBytes: Long? = null,
    val latencyMs: Long? = null,
    val detail: String? = null,
)

fun interface ProbeSnapshotOnHost
{
    /** POST /api/v1/snapshot/probe on the host agent. Null = agent unreachable. */
    suspend fun probe(
        agentUrl: String,
        token: String,
        samples: List<SnapshotHostSpeedSample>,
    ): List<SnapshotHostSpeedResult>?
}
