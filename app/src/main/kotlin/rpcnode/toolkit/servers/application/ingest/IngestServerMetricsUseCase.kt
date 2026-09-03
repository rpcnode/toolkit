package rpcnode.toolkit.servers.application.ingest

import java.time.Clock
import java.time.Instant
import rpcnode.toolkit.servers.domain.model.ServerDisk
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.model.ServerMetrics
import rpcnode.toolkit.servers.domain.model.metricsStatus
import rpcnode.toolkit.servers.domain.repository.ServerMetricsRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

data class IncomingHostDisk(
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

data class IncomingHostMetrics(
    val serverId: String = "",
    val agentUrl: String = "",
    val cpuPct: Double,
    val load1: Double,
    val loadPct: Double,
    val ncpu: Int,
    val memPct: Double,
    val memUsedMb: Double,
    val memTotalMb: Double,
    val diskUsedPct: Double,
    val diskUsedGb: Double,
    val diskTotalGb: Double,
    val disks: List<IncomingHostDisk>,
    val os: String,
    val arch: String,
    val collectedAt: String = "",
    val agentVersion: String = "",
    val netRxMbps: Double = 0.0,
    val netTxMbps: Double = 0.0,
    val diskReadIops: Double = 0.0,
    val diskWriteIops: Double = 0.0,
    val diskReadMbS: Double = 0.0,
    val diskWriteMbS: Double = 0.0,
    val diskUtilPct: Double = 0.0,
    val diskBusy: String = "",
)

sealed interface IngestServerMetricsResult
{
    data class Ok(val serverId: ServerId, val status: String) : IngestServerMetricsResult
    data object Unauthorized : IngestServerMetricsResult
}

class IngestServerMetricsUseCase(
    private val servers: ServerRepository,
    private val metrics: ServerMetricsRepository,
    private val clock: Clock = Clock.systemUTC(),
)
{
    suspend operator fun invoke(tokenRaw: String, incoming: IncomingHostMetrics): IngestServerMetricsResult
    {
        val token = tokenRaw.trim()
        if (token.isEmpty())
        {
            return IngestServerMetricsResult.Unauthorized
        }
        val claimed = ServerId.parse(incoming.serverId)
        val server = if (claimed != null)
        {
            val found = servers.find(claimed) ?: return IngestServerMetricsResult.Unauthorized
            if (found.agentKey != token)
            {
                return IngestServerMetricsResult.Unauthorized
            }
            found
        }
        else
        {
            servers.findByAgentKey(token) ?: return IngestServerMetricsResult.Unauthorized
        }
        if (!server.isActive())
        {
            return IngestServerMetricsResult.Unauthorized
        }
        val now = Instant.now(clock).toString()
        val disks = incoming.disks.map {
            ServerDisk(
                name = it.name,
                mount = it.mount,
                freeGb = it.freeGb,
                totalGb = it.totalGb,
                usedPct = it.usedPct,
                readIops = it.readIops,
                writeIops = it.writeIops,
                readMbS = it.readMbS,
                writeMbS = it.writeMbS,
                utilPct = it.utilPct,
            )
        }
        val diskTotal = if (incoming.diskTotalGb > 0) incoming.diskTotalGb else disks.sumOf { it.totalGb }
        val diskUsed = if (incoming.diskUsedGb > 0) incoming.diskUsedGb else disks.sumOf { (it.totalGb - it.freeGb).coerceAtLeast(0.0) }
        val diskPct = if (incoming.diskUsedPct > 0) incoming.diskUsedPct else if (diskTotal > 0) diskUsed / diskTotal * 100.0 else 0.0
        val collectedAt = incoming.collectedAt.trim().ifBlank { now }
        val snap = ServerMetrics(
            serverId = server.id,
            cpuPct = incoming.cpuPct,
            loadPct = incoming.loadPct,
            ncpu = incoming.ncpu,
            memPct = incoming.memPct,
            memUsedMb = incoming.memUsedMb,
            memTotalMb = incoming.memTotalMb,
            diskUsedPct = diskPct,
            diskUsedGb = diskUsed,
            diskTotalGb = diskTotal,
            load1 = incoming.load1,
            disks = disks,
            os = incoming.os,
            arch = incoming.arch,
            collectedAt = collectedAt,
            lastSeenAt = now,
            netRxMbps = incoming.netRxMbps,
            netTxMbps = incoming.netTxMbps,
            diskReadIops = incoming.diskReadIops,
            diskWriteIops = incoming.diskWriteIops,
            diskReadMbS = incoming.diskReadMbS,
            diskWriteMbS = incoming.diskWriteMbS,
            diskUtilPct = incoming.diskUtilPct,
            diskBusy = incoming.diskBusy,
        )
        metrics.upsert(snap)
        val version = incoming.agentVersion.trim()
        if (version.isNotEmpty() && version != server.agentVersion)
        {
            servers.setAgentVersion(server.id, version, now)
        }
        return IngestServerMetricsResult.Ok(serverId = server.id, status = metricsStatus(snap, clock.millis()))
    }
}
