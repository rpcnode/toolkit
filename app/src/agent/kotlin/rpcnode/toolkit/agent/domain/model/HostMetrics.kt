package rpcnode.toolkit.agent.domain.model

data class HostDisk(
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

data class HostMetrics(
    val cpuPct: Double,
    val load1: Double,
    val loadPct: Double,
    val ncpu: Int,
    val memPct: Double,
    val memUsedMb: Double,
    val memTotalMb: Double,
    val disks: List<HostDisk>,
    val os: String,
    val arch: String,
    val netRxMbps: Double = 0.0,
    val netTxMbps: Double = 0.0,
    val diskReadIops: Double = 0.0,
    val diskWriteIops: Double = 0.0,
    val diskReadMbS: Double = 0.0,
    val diskWriteMbS: Double = 0.0,
    val diskUtilPct: Double = 0.0,
    val diskBusy: String = "",
)
{
    val diskTotalGb: Double get() = disks.sumOf { it.totalGb }

    val diskUsedGb: Double get() = disks.sumOf { (it.totalGb - it.freeGb).coerceAtLeast(0.0) }

    val diskUsedPct: Double
        get() = if (diskTotalGb <= 0) 0.0 else diskUsedGb / diskTotalGb * 100.0
}

data class CpuCounters(
    val idle: Long,
    val total: Long,
)

fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

fun parseLoad1(raw: String): Double
{
    val first = raw.trim().split(Regex("\\s+")).firstOrNull() ?: return 0.0
    return first.toDoubleOrNull() ?: 0.0
}

fun parseMemKb(raw: String): Pair<Long, Long>
{
    var total = 0L
    var avail = 0L
    for (line in raw.lineSequence())
    {
        val fields = line.split(Regex("\\s+"))
        if (fields.size < 2)
        {
            continue
        }
        val v = fields[1].toLongOrNull() ?: continue
        when (fields[0])
        {
            "MemTotal:" -> total = v
            "MemAvailable:" -> avail = v
        }
    }
    return total to avail
}

fun parseCpuCounters(raw: String): CpuCounters?
{
    val line = raw.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
    val fields = line.split(Regex("\\s+")).drop(1)
    if (fields.size < 4)
    {
        return null
    }
    val vals = fields.mapNotNull { it.toLongOrNull() }
    if (vals.size < 4)
    {
        return null
    }
    return CpuCounters(idle = vals[3], total = vals.sum())
}

fun cpuBusyPct(prev: CpuCounters, now: CpuCounters): Double
{
    val dIdle = now.idle - prev.idle
    val dTotal = now.total - prev.total
    if (dTotal <= 0)
    {
        return 0.0
    }
    return (100.0 * (1.0 - dIdle.toDouble() / dTotal.toDouble())).coerceIn(0.0, 100.0)
}

internal fun skipSpaceMount(mp: String, fstype: String): Boolean
{
    val fs = fstype.lowercase()
    val skipFs = listOf(
        "tmpfs", "devtmpfs", "proc", "sysfs", "cgroup", "cgroup2", "overlay",
        "squashfs", "autofs", "fuse", "nfs", "tracefs", "debugfs", "securityfs",
        "ramfs", "efivarfs", "bpf", "nsfs",
    )
    if (skipFs.any { fs == it || fs.startsWith("$it.") })
    {
        return true
    }
    val n = mp.trim()
    if (n.isEmpty() || n == "/boot" || n.startsWith("/boot/"))
    {
        return true
    }
    for (p in listOf("/snap", "/run", "/sys", "/proc", "/dev", "/var/lib/docker"))
    {
        if (n == p || n.startsWith("$p/"))
        {
            return true
        }
    }
    return false
}

internal fun isWholeDiskName(name: String): Boolean
{
    val n = name.lowercase().trim()
    if (n.isEmpty())
    {
        return false
    }
    if (listOf("loop", "ram", "dm-", "md", "sr", "zram", "nbd", "fd").any { n.startsWith(it) })
    {
        return false
    }
    if (n.startsWith("nvme"))
    {
        if ('p' in n || 'c' in n)
        {
            return false
        }
        return 'n' in n
    }
    if (n.startsWith("mmcblk"))
    {
        val rest = n.removePrefix("mmcblk")
        return rest.isNotEmpty() && 'p' !in rest && rest.all { it.isDigit() }
    }
    for (p in listOf("sd", "vd", "hd"))
    {
        if (n.startsWith(p) && n.length > p.length)
        {
            return n.substring(p.length).all { it.isLetter() }
        }
    }
    if (n.startsWith("xvd") && n.length > 3)
    {
        return n.substring(3).all { it.isLetter() }
    }
    return false
}

internal fun wholeDiskFromBase(base: String): String
{
    val n = base.lowercase().trim()
    if (isWholeDiskName(n))
    {
        return n
    }
    if (n.startsWith("nvme"))
    {
        val i = n.lastIndexOf('p')
        if (i > 0 && n.substring(i + 1).all { it.isDigit() })
        {
            val parent = n.substring(0, i)
            if (isWholeDiskName(parent))
            {
                return parent
            }
        }
    }
    if (n.startsWith("mmcblk"))
    {
        val i = n.indexOf('p')
        if (i > 0 && isWholeDiskName(n.substring(0, i)))
        {
            return n.substring(0, i)
        }
    }
    for (p in listOf("sd", "vd", "hd", "xvd"))
    {
        if (!n.startsWith(p) || n.length <= p.length)
        {
            continue
        }
        val rest = n.substring(p.length)
        val letters = rest.takeWhile { it.isLetter() }
        val parent = p + letters
        if (isWholeDiskName(parent))
        {
            return parent
        }
    }
    return ""
}

internal fun wholeDiskForDev(source: String): String
{
    val src = source.trim()
    if (src.isEmpty() || src == "none")
    {
        return ""
    }
    val base = src.substringAfterLast('/')
    return wholeDiskFromBase(base)
}

/** Real block devices from /proc/mounts. [space] returns total/avail bytes for a mountpoint. */
fun disksFromMounts(mountsRaw: String, space: (String) -> Pair<Long, Long>?): List<HostDisk>
{
    val byDisk = LinkedHashMap<String, HostDisk>()
    for (line in mountsRaw.lineSequence())
    {
        val fields = line.split(Regex("\\s+"))
        if (fields.size < 3)
        {
            continue
        }
        val src = fields[0]
        val mp = fields[1]
        val fstype = fields[2]
        if (skipSpaceMount(mp, fstype))
        {
            continue
        }
        val disk = wholeDiskForDev(src)
        if (disk.isEmpty())
        {
            continue
        }
        val bytes = space(mp) ?: continue
        val total = bytes.first
        val avail = bytes.second
        if (total <= 0)
        {
            continue
        }
        val totalGb = total / (1024.0 * 1024.0 * 1024.0)
        val freeGb = avail / (1024.0 * 1024.0 * 1024.0)
        val usedPct = if (total >= avail) (total - avail).toDouble() * 100.0 / total else 0.0
        val prev = byDisk[disk]
        if (prev != null && prev.totalGb >= totalGb)
        {
            continue
        }
        byDisk[disk] = HostDisk(
            name = disk,
            mount = mp,
            freeGb = round2(freeGb),
            totalGb = round2(totalGb),
            usedPct = round2(usedPct),
        )
    }
    return byDisk.values.toList()
}
