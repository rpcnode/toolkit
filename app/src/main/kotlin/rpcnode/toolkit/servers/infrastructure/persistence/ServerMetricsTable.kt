package rpcnode.toolkit.servers.infrastructure.persistence

import org.jetbrains.exposed.sql.Table

object ServerMetricsTable : Table("server_metrics")
{
    val serverId = varchar("server_id", 64)
    val cpuPct = double("cpu_pct").default(0.0)
    val loadPct = double("load_pct").default(0.0)
    val ncpu = integer("ncpu").default(0)
    val memPct = double("mem_pct").default(0.0)
    val memUsedMb = double("mem_used_mb").default(0.0)
    val memTotalMb = double("mem_total_mb").default(0.0)
    val diskUsedPct = double("disk_used_pct").default(0.0)
    val diskUsedGb = double("disk_used_gb").default(0.0)
    val diskTotalGb = double("disk_total_gb").default(0.0)
    val load1 = double("load_1").default(0.0)
    val disksJson = text("disks_json").default("[]")
    val os = varchar("os", 64).default("")
    val arch = varchar("arch", 64).default("")
    val collectedAt = varchar("collected_at", 64)
    val lastSeenAt = varchar("last_seen_at", 64)
    val netRxMbps = double("net_rx_mbps").default(0.0)
    val netTxMbps = double("net_tx_mbps").default(0.0)
    val diskReadIops = double("disk_read_iops").default(0.0)
    val diskWriteIops = double("disk_write_iops").default(0.0)
    val diskReadMbS = double("disk_read_mb_s").default(0.0)
    val diskWriteMbS = double("disk_write_mb_s").default(0.0)
    val diskUtilPct = double("disk_util_pct").default(0.0)
    val diskBusy = varchar("disk_busy", 64).default("")

    override val primaryKey = PrimaryKey(serverId)
}
