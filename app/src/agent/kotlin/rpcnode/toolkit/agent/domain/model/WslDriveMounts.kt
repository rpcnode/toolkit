package rpcnode.toolkit.agent.domain.model

/**
 * WSL exposes Windows drives (`C:\`, `J:\` …) as 9p/drvfs mounts under /mnt/<letter>. They are not
 * block devices, so `lsblk` never lists them — without this the node installer only sees the
 * distro's own virtual disks.
 */
fun windowsDriveMounts(mountsRaw: String, space: (String) -> Pair<Long, Long>?): List<MountPoint>
{
    val out = LinkedHashMap<String, MountPoint>()
    for (line in mountsRaw.lineSequence())
    {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 3)
        {
            continue
        }
        val letter = windowsDriveLetter(fields[0], fields[2]) ?: continue
        val target = unescapeMountField(fields[1])
        val (total, avail) = space(target) ?: continue
        if (total <= 0)
        {
            continue
        }
        out.getOrPut(target) {
            MountPoint(
                target = target,
                source = "$letter:\\",
                fstype = fields[2],
                sizeBytes = total,
                availBytes = avail,
                availHuman = formatSizeHuman(avail),
                usedPct = if (total >= avail) (total - avail).toDouble() * 100.0 / total else 0.0,
                diskName = "win-${letter.lowercaseChar()}",
                diskPath = "$letter:\\",
            )
        }
    }
    return out.values.toList()
}

private val DRIVE_SOURCE = Regex("^([A-Za-z]):(?:\\\\134|\\\\)?$")
private val OCTAL_ESCAPE = Regex("\\\\([0-7]{3})")

/** `C:\` in /proc/mounts is written `C:\134`; also accept a plain `C:`. */
private fun windowsDriveLetter(source: String, fstype: String): Char?
{
    if (fstype != "9p" && fstype != "drvfs")
    {
        return null
    }
    val m = DRIVE_SOURCE.matchEntire(source) ?: return null
    return m.groupValues[1].single().uppercaseChar()
}

private fun unescapeMountField(raw: String): String =
    OCTAL_ESCAPE.replace(raw) { it.groupValues[1].toInt(8).toChar().toString() }

/**
 * `lsblk` reports a single mountpoint per device; in WSL the root disk is bind-mounted at
 * /mnt/wslg/distro as well and that one wins. Show it as "/" so the root disk is recognised.
 */
fun fixWslRootMount(mounts: List<MountPoint>): List<MountPoint>
{
    if (mounts.any { it.target == "/" })
    {
        return mounts
    }
    return mounts.map { if (it.target == "/mnt/wslg/distro") it.copy(target = "/") else it }
}
