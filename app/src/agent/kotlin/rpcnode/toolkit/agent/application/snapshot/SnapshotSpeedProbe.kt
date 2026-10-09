package rpcnode.toolkit.agent.application.snapshot

/** One timed sample download from a mirror URL (bytes are discarded). */
data class SnapshotSpeedProbeReading(
    val available: Boolean,
    val bytesPerSec: Long? = null,
    val sampleBytes: Long? = null,
    val latencyMs: Long? = null,
    val detail: String? = null,
)

fun interface SnapshotSpeedProbe
{
    suspend fun probe(url: String): SnapshotSpeedProbeReading
}

data class SnapshotSpeedSampleRequest(
    val id: String,
    val url: String,
)

data class SnapshotSpeedSampleResult(
    val id: String,
    val available: Boolean,
    val bytesPerSec: Long? = null,
    val sampleBytes: Long? = null,
    val latencyMs: Long? = null,
    val detail: String? = null,
)
