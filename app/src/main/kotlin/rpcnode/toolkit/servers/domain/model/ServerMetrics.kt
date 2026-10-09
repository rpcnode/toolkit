package rpcnode.toolkit.servers.domain.model

data class ServerDisk(
    val name: String,
    val mount: String,
    val freeGb: Double,
    val totalGb: Double,
    val usedPct: Double,
    val readIops: Double = 0.0,
    val writeIops: Double = 0.0,
    val readMbS: Double = 0.0,
    val writeMbS: Double = 0.0,
    val utilPct: Double = 0.0,
)

data class ServerMetrics(
    val serverId: ServerId,
    val cpuPct: Double,
    val loadPct: Double,
    val ncpu: Int,
    val memPct: Double,
    val memUsedMb: Double,
    val memTotalMb: Double,
    val diskUsedPct: Double,
    val diskUsedGb: Double,
    val diskTotalGb: Double,
    val load1: Double,
    val disks: List<ServerDisk>,
    val os: String,
    val arch: String,
    val collectedAt: String,
    val lastSeenAt: String,
    val netRxMbps: Double = 0.0,
    val netTxMbps: Double = 0.0,
    val diskReadIops: Double = 0.0,
    val diskWriteIops: Double = 0.0,
    val diskReadMbS: Double = 0.0,
    val diskWriteMbS: Double = 0.0,
    val diskUtilPct: Double = 0.0,
    val diskBusy: String = "",
)

fun metricsStatus(metrics: ServerMetrics?, nowEpochMs: Long = System.currentTimeMillis()): String
{
    if (metrics == null)
    {
        return "unknown"
    }
    val seen = parseEpochMs(metrics.lastSeenAt) ?: return "unknown"
    val age = nowEpochMs - seen
    if (age <= 3 * 60_000)
    {
        return "online"
    }
    if (age <= 15 * 60_000)
    {
        return "stale"
    }
    return "offline"
}

private fun parseEpochMs(iso: String): Long?
{
    return try
    {
        java.time.Instant.parse(iso).toEpochMilli()
    }
    catch (_: Exception)
    {
        null
    }
}
