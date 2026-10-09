package rpcnode.toolkit.agent.domain.model

/** Counters from /proc/net/dev (sum of physical NICs). */
data class NetCounters(
    val rxBytes: Long,
    val txBytes: Long,
)

/** Counters from one whole-disk line of /proc/diskstats. */
data class DiskIoCounters(
    val reads: Long,
    val writes: Long,
    val readSectors: Long,
    val writeSectors: Long,
    val ioMs: Long,
)

data class DiskIoRate(
    val readIops: Double,
    val writeIops: Double,
    val readMbS: Double,
    val writeMbS: Double,
    val utilPct: Double,
)

data class HostIoRates(
    val netRxMbps: Double,
    val netTxMbps: Double,
    val byDisk: Map<String, DiskIoRate>,
    val diskReadIops: Double,
    val diskWriteIops: Double,
    val diskReadMbS: Double,
    val diskWriteMbS: Double,
    val diskUtilPct: Double,
    val diskBusy: String,
)

private val SKIP_NET_IFACES = listOf(
    "lo", "docker", "veth", "br-", "virbr", "tun", "tap", "wg", "cni", "flannel", "cali", "nodelocal",
)

fun skipNetIface(name: String): Boolean
{
    val n = name.trim().lowercase()
    if (n.isEmpty()) return true
    return SKIP_NET_IFACES.any { n == it || n.startsWith(it) }
}

/** Parse /proc/net/dev → sum of rx/tx bytes on non-virtual interfaces. */
fun parseNetCounters(raw: String): NetCounters
{
    var rx = 0L
    var tx = 0L
    for (line in raw.lineSequence())
    {
        val trimmed = line.trim()
        if (!trimmed.contains(':')) continue
        val iface = trimmed.substringBefore(':').trim()
        if (skipNetIface(iface)) continue
        val fields = trimmed.substringAfter(':').trim().split(Regex("\\s+"))
        if (fields.size < 9) continue
        val r = fields[0].toLongOrNull() ?: continue
        val t = fields[8].toLongOrNull() ?: continue
        rx += r
        tx += t
    }
    return NetCounters(rxBytes = rx, txBytes = tx)
}

/**
 * Parse /proc/diskstats for whole disks only.
 * Field layout: major minor name reads … rd_sectors … writes … wr_sectors … io_ms …
 */
fun parseDiskIoCounters(raw: String): Map<String, DiskIoCounters>
{
    val out = linkedMapOf<String, DiskIoCounters>()
    for (line in raw.lineSequence())
    {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 14) continue
        val name = fields[2]
        if (!isWholeDiskName(name)) continue
        val reads = fields[3].toLongOrNull() ?: continue
        val readSectors = fields[5].toLongOrNull() ?: continue
        val writes = fields[7].toLongOrNull() ?: continue
        val writeSectors = fields[9].toLongOrNull() ?: continue
        val ioMs = fields[12].toLongOrNull() ?: continue
        out[name] = DiskIoCounters(
            reads = reads,
            writes = writes,
            readSectors = readSectors,
            writeSectors = writeSectors,
            ioMs = ioMs,
        )
    }
    return out
}

fun netRatesMbps(prev: NetCounters, now: NetCounters, dtSec: Double): Pair<Double, Double>
{
    if (dtSec <= 0) return 0.0 to 0.0
    val rx = ((now.rxBytes - prev.rxBytes).coerceAtLeast(0).toDouble() * 8.0) / dtSec / 1_000_000.0
    val tx = ((now.txBytes - prev.txBytes).coerceAtLeast(0).toDouble() * 8.0) / dtSec / 1_000_000.0
    return round2(rx) to round2(tx)
}

fun diskIoRate(prev: DiskIoCounters, now: DiskIoCounters, dtSec: Double): DiskIoRate
{
    if (dtSec <= 0)
    {
        return DiskIoRate(0.0, 0.0, 0.0, 0.0, 0.0)
    }
    val dReads = (now.reads - prev.reads).coerceAtLeast(0)
    val dWrites = (now.writes - prev.writes).coerceAtLeast(0)
    val dRdSec = (now.readSectors - prev.readSectors).coerceAtLeast(0)
    val dWrSec = (now.writeSectors - prev.writeSectors).coerceAtLeast(0)
    val dIoMs = (now.ioMs - prev.ioMs).coerceAtLeast(0)
    val readIops = dReads / dtSec
    val writeIops = dWrites / dtSec
    val sectorBytes = 512.0
    val readMbS = dRdSec * sectorBytes / dtSec / (1024.0 * 1024.0)
    val writeMbS = dWrSec * sectorBytes / dtSec / (1024.0 * 1024.0)
    val utilPct = (dIoMs / (dtSec * 1000.0) * 100.0).coerceIn(0.0, 100.0)
    return DiskIoRate(
        readIops = round2(readIops),
        writeIops = round2(writeIops),
        readMbS = round2(readMbS),
        writeMbS = round2(writeMbS),
        utilPct = round2(utilPct),
    )
}

fun hostIoRates(
    prevNet: NetCounters?,
    nowNet: NetCounters,
    prevDisk: Map<String, DiskIoCounters>?,
    nowDisk: Map<String, DiskIoCounters>,
    dtSec: Double,
): HostIoRates
{
    val (rx, tx) = if (prevNet != null) netRatesMbps(prevNet, nowNet, dtSec) else 0.0 to 0.0
    val byDisk = linkedMapOf<String, DiskIoRate>()
    if (prevDisk != null && dtSec > 0)
    {
        for ((name, now) in nowDisk)
        {
            val prev = prevDisk[name] ?: continue
            byDisk[name] = diskIoRate(prev, now, dtSec)
        }
    }
    var readIops = 0.0
    var writeIops = 0.0
    var readMbS = 0.0
    var writeMbS = 0.0
    var hottest = ""
    var hottestUtil = -1.0
    for ((name, rate) in byDisk)
    {
        readIops += rate.readIops
        writeIops += rate.writeIops
        readMbS += rate.readMbS
        writeMbS += rate.writeMbS
        if (rate.utilPct > hottestUtil)
        {
            hottestUtil = rate.utilPct
            hottest = name
        }
    }
    return HostIoRates(
        netRxMbps = rx,
        netTxMbps = tx,
        byDisk = byDisk,
        diskReadIops = round2(readIops),
        diskWriteIops = round2(writeIops),
        diskReadMbS = round2(readMbS),
        diskWriteMbS = round2(writeMbS),
        diskUtilPct = if (hottestUtil < 0) 0.0 else round2(hottestUtil),
        diskBusy = hottest,
    )
}

fun mergeDiskCapacityWithIo(capacity: List<HostDisk>, rates: Map<String, DiskIoRate>): List<HostDisk>
{
    if (rates.isEmpty()) return capacity
    val seen = capacity.map { it.name }.toMutableSet()
    val merged = capacity.map { d ->
        val r = rates[d.name] ?: return@map d
        d.copy(
            readIops = r.readIops,
            writeIops = r.writeIops,
            readMbS = r.readMbS,
            writeMbS = r.writeMbS,
            utilPct = r.utilPct,
        )
    }.toMutableList()
    for ((name, r) in rates)
    {
        if (name in seen) continue
        merged += HostDisk(
            name = name,
            mount = "",
            freeGb = 0.0,
            totalGb = 0.0,
            usedPct = 0.0,
            readIops = r.readIops,
            writeIops = r.writeIops,
            readMbS = r.readMbS,
            writeMbS = r.writeMbS,
            utilPct = r.utilPct,
        )
    }
    return merged
}
