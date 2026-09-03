package rpcnode.toolkit.agent.infrastructure.proc

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import rpcnode.toolkit.agent.application.disks.HostDiskProbe
import rpcnode.toolkit.agent.domain.model.HostDiskInventory
import rpcnode.toolkit.agent.domain.model.disksFromMounts
import rpcnode.toolkit.agent.domain.model.formatSizeHuman
import rpcnode.toolkit.agent.domain.model.plannedMountForDisk
import rpcnode.toolkit.agent.domain.model.unusedFromInventory
import java.nio.file.Files
import java.nio.file.Path

/** Runs `lsblk -Jbn` on the host; falls back to /proc/mounts when lsblk is missing. */
class LsblkHostDiskProbe : HostDiskProbe
{
    override fun inventory(): HostDiskInventory
    {
        val lsblk = runCommand("lsblk", "-Jbn", "-o", "NAME,PATH,MODEL,SIZE,TYPE,ROTA,TRAN,MOUNTPOINT,FSTYPE,FSAVAIL,FSUSE%")
        if (lsblk.isNotBlank())
        {
            return LsblkHostDiskParser.parse(lsblk)
        }
        return fallbackFromMounts()
    }

    private fun fallbackFromMounts(): HostDiskInventory
    {
        val mountsRaw = readProc("mounts").orEmpty()
        val metricsDisks = disksFromMounts(mountsRaw, ::statMount)
        val mounts = metricsDisks.map {
            rpcnode.toolkit.agent.domain.model.MountPoint(
                target = it.mount,
                source = "/dev/${it.name}",
                availBytes = (it.freeGb * 1024 * 1024 * 1024).toLong(),
                availHuman = formatSizeHuman((it.freeGb * 1024 * 1024 * 1024).toLong()),
                usedPct = it.usedPct,
                diskName = it.name,
                diskPath = "/dev/${it.name}",
            )
        }
        val disks = metricsDisks.map {
            rpcnode.toolkit.agent.domain.model.BlockDevice(
                name = it.name,
                path = "/dev/${it.name}",
                sizeBytes = (it.totalGb * 1024 * 1024 * 1024).toLong(),
                sizeHuman = formatSizeHuman((it.totalGb * 1024 * 1024 * 1024).toLong()),
                mountpoint = it.mount,
                fsavailBytes = (it.freeGb * 1024 * 1024 * 1024).toLong(),
                fsusedPct = it.usedPct,
                plannedMount = plannedMountForDisk(it.name),
            )
        }
        return HostDiskInventory(
            disks = disks,
            mounts = mounts,
            unused = unusedFromInventory(disks, mounts),
        )
    }

    private fun statMount(mp: String): Pair<Long, Long>?
    {
        return try
        {
            val store = Files.getFileStore(Path.of(mp))
            val total = store.totalSpace
            val avail = store.usableSpace
            if (total <= 0) null else total to avail
        }
        catch (_: Exception)
        {
            null
        }
    }

    private fun readProc(name: String): String?
    {
        for (root in listOf("/host/proc", "/proc"))
        {
            val file = Path.of(root, name)
            if (Files.isRegularFile(file))
            {
                return Files.readString(file)
            }
        }
        return null
    }

    private fun runCommand(vararg cmd: String): String
    {
        return try
        {
            val proc = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            val out = ByteArrayOutputStream()
            proc.inputStream.transferTo(out)
            if (!proc.waitFor(15, TimeUnit.SECONDS))
            {
                proc.destroyForcibly()
                return ""
            }
            if (proc.exitValue() != 0)
            {
                return ""
            }
            out.toString(Charsets.UTF_8)
        }
        catch (_: Exception)
        {
            ""
        }
    }
}
