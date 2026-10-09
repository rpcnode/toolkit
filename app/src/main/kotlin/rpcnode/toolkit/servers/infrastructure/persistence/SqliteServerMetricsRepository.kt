package rpcnode.toolkit.servers.infrastructure.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import rpcnode.toolkit.servers.domain.model.ServerDisk
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.model.ServerMetrics
import rpcnode.toolkit.servers.domain.repository.ServerMetricsRepository
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

@Serializable
private data class StoredServerDisk(
    val name: String,
    val mount: String,
    @SerialName("free_gb") val freeGb: Double,
    @SerialName("total_gb") val totalGb: Double,
    @SerialName("used_pct") val usedPct: Double,
    @SerialName("read_iops") val readIops: Double = 0.0,
    @SerialName("write_iops") val writeIops: Double = 0.0,
    @SerialName("read_mb_s") val readMbS: Double = 0.0,
    @SerialName("write_mb_s") val writeMbS: Double = 0.0,
    @SerialName("util_pct") val utilPct: Double = 0.0,
)

class SqliteServerMetricsRepository(
    private val db: ToolkitDatabase,
) : ServerMetricsRepository
{
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun find(serverId: ServerId): ServerMetrics? = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ServerMetricsTable.selectAll()
                .where { ServerMetricsTable.serverId eq serverId.value }
                .singleOrNull()
                ?.toDomain()
        }
    }

    override suspend fun upsert(metrics: ServerMetrics) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            val disksJson = json.encodeToString(metrics.disks.map { it.toStored() })
            val existing = ServerMetricsTable.selectAll()
                .where { ServerMetricsTable.serverId eq metrics.serverId.value }
                .singleOrNull()
            if (existing == null)
            {
                ServerMetricsTable.insert {
                    it[serverId] = metrics.serverId.value
                    it[cpuPct] = metrics.cpuPct
                    it[loadPct] = metrics.loadPct
                    it[ncpu] = metrics.ncpu
                    it[memPct] = metrics.memPct
                    it[memUsedMb] = metrics.memUsedMb
                    it[memTotalMb] = metrics.memTotalMb
                    it[diskUsedPct] = metrics.diskUsedPct
                    it[diskUsedGb] = metrics.diskUsedGb
                    it[diskTotalGb] = metrics.diskTotalGb
                    it[load1] = metrics.load1
                    it[this.disksJson] = disksJson
                    it[os] = metrics.os
                    it[arch] = metrics.arch
                    it[collectedAt] = metrics.collectedAt
                    it[lastSeenAt] = metrics.lastSeenAt
                    it[netRxMbps] = metrics.netRxMbps
                    it[netTxMbps] = metrics.netTxMbps
                    it[diskReadIops] = metrics.diskReadIops
                    it[diskWriteIops] = metrics.diskWriteIops
                    it[diskReadMbS] = metrics.diskReadMbS
                    it[diskWriteMbS] = metrics.diskWriteMbS
                    it[diskUtilPct] = metrics.diskUtilPct
                    it[diskBusy] = metrics.diskBusy
                }
            }
            else
            {
                ServerMetricsTable.update({ ServerMetricsTable.serverId eq metrics.serverId.value }) {
                    it[cpuPct] = metrics.cpuPct
                    it[loadPct] = metrics.loadPct
                    it[ncpu] = metrics.ncpu
                    it[memPct] = metrics.memPct
                    it[memUsedMb] = metrics.memUsedMb
                    it[memTotalMb] = metrics.memTotalMb
                    it[diskUsedPct] = metrics.diskUsedPct
                    it[diskUsedGb] = metrics.diskUsedGb
                    it[diskTotalGb] = metrics.diskTotalGb
                    it[load1] = metrics.load1
                    it[this.disksJson] = disksJson
                    it[os] = metrics.os
                    it[arch] = metrics.arch
                    it[collectedAt] = metrics.collectedAt
                    it[lastSeenAt] = metrics.lastSeenAt
                    it[netRxMbps] = metrics.netRxMbps
                    it[netTxMbps] = metrics.netTxMbps
                    it[diskReadIops] = metrics.diskReadIops
                    it[diskWriteIops] = metrics.diskWriteIops
                    it[diskReadMbS] = metrics.diskReadMbS
                    it[diskWriteMbS] = metrics.diskWriteMbS
                    it[diskUtilPct] = metrics.diskUtilPct
                    it[diskBusy] = metrics.diskBusy
                }
            }
            Unit
        }
    }

    private fun ResultRow.toDomain() = ServerMetrics(
        serverId = ServerId.parse(this[ServerMetricsTable.serverId]) ?: error("invalid server id"),
        cpuPct = this[ServerMetricsTable.cpuPct],
        loadPct = this[ServerMetricsTable.loadPct],
        ncpu = this[ServerMetricsTable.ncpu],
        memPct = this[ServerMetricsTable.memPct],
        memUsedMb = this[ServerMetricsTable.memUsedMb],
        memTotalMb = this[ServerMetricsTable.memTotalMb],
        diskUsedPct = this[ServerMetricsTable.diskUsedPct],
        diskUsedGb = this[ServerMetricsTable.diskUsedGb],
        diskTotalGb = this[ServerMetricsTable.diskTotalGb],
        load1 = this[ServerMetricsTable.load1],
        disks = parseDisks(this[ServerMetricsTable.disksJson]),
        os = this[ServerMetricsTable.os],
        arch = this[ServerMetricsTable.arch],
        collectedAt = this[ServerMetricsTable.collectedAt],
        lastSeenAt = this[ServerMetricsTable.lastSeenAt],
        netRxMbps = this[ServerMetricsTable.netRxMbps],
        netTxMbps = this[ServerMetricsTable.netTxMbps],
        diskReadIops = this[ServerMetricsTable.diskReadIops],
        diskWriteIops = this[ServerMetricsTable.diskWriteIops],
        diskReadMbS = this[ServerMetricsTable.diskReadMbS],
        diskWriteMbS = this[ServerMetricsTable.diskWriteMbS],
        diskUtilPct = this[ServerMetricsTable.diskUtilPct],
        diskBusy = this[ServerMetricsTable.diskBusy],
    )

    private fun parseDisks(raw: String): List<ServerDisk>
    {
        return try
        {
            json.decodeFromString<List<StoredServerDisk>>(raw).map {
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
        }
        catch (_: Exception)
        {
            emptyList()
        }
    }

    private fun ServerDisk.toStored() = StoredServerDisk(
        name = name,
        mount = mount,
        freeGb = freeGb,
        totalGb = totalGb,
        usedPct = usedPct,
        readIops = readIops,
        writeIops = writeIops,
        readMbS = readMbS,
        writeMbS = writeMbS,
        utilPct = utilPct,
    )
}
