package rpcnode.toolkit.agent.domain.model

/** Format byte counts for inventory (binary IEC). */
fun formatSizeHuman(bytes: Long): String
{
    if (bytes <= 0)
    {
        return "0B"
    }
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex)
    {
        v /= 1024.0
        i++
    }
    val rounded = if (i == 0) v.toLong().toString() else "%.1f".format(v)
    return "$rounded${units[i]}"
}

internal fun plannedMountForDisk(name: String): String
{
    val trimmed = name.trim().removeSuffix("n1")
    return if (trimmed.isEmpty()) "" else "/data/$trimmed"
}

internal fun isPreferredDisk(tran: String, rota: Boolean): Boolean =
    tran.equals("nvme", ignoreCase = true) && !rota

/** Whole-disk member of md RAID — not a JBOD data target for chain roles. */
internal fun isRaidMemberFsType(fstype: String): Boolean =
    fstype.equals("linux_raid_member", ignoreCase = true)

internal fun unusedFromInventory(
    disks: List<BlockDevice>,
    mounts: List<MountPoint>,
): List<BlockDevice>
{
    val rootDisk = mounts.firstOrNull { it.target == "/" }?.diskName.orEmpty()
    val dataDisks = mounts
        .filter { it.target.isNotEmpty() && it.target != "/" && !it.target.startsWith("/boot") }
        .map { it.diskName }
        .filter { it.isNotEmpty() }
        .toSet()
    return disks.filter { d ->
        val n = d.name.lowercase()
        if (!n.contains("nvme"))
        {
            return@filter false
        }
        if (rootDisk.isNotEmpty() && d.name == rootDisk)
        {
            return@filter false
        }
        if (dataDisks.contains(d.name))
        {
            return@filter false
        }
        if (d.fstype.isNotBlank())
        {
            return@filter false
        }
        val mp = d.mountpoint
        if (mp.isNotEmpty() && mp != "/" && !mp.startsWith("/boot"))
        {
            return@filter false
        }
        true
    }.map { d ->
        d.copy(plannedMount = d.plannedMount.ifBlank { plannedMountForDisk(d.name) })
    }
}
