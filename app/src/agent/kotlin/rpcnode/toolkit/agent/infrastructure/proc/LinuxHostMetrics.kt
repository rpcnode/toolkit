package rpcnode.toolkit.agent.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import rpcnode.toolkit.agent.application.metrics.HostMetricsSource
import rpcnode.toolkit.agent.domain.model.CpuCounters
import rpcnode.toolkit.agent.domain.model.DiskIoCounters
import rpcnode.toolkit.agent.domain.model.HostMetrics
import rpcnode.toolkit.agent.domain.model.NetCounters
import rpcnode.toolkit.agent.domain.model.cpuBusyPct
import rpcnode.toolkit.agent.domain.model.disksFromMounts
import rpcnode.toolkit.agent.domain.model.hostIoRates
import rpcnode.toolkit.agent.domain.model.mergeDiskCapacityWithIo
import rpcnode.toolkit.agent.domain.model.parseCpuCounters
import rpcnode.toolkit.agent.domain.model.parseDiskIoCounters
import rpcnode.toolkit.agent.domain.model.parseLoad1
import rpcnode.toolkit.agent.domain.model.parseMemKb
import rpcnode.toolkit.agent.domain.model.parseNetCounters
import rpcnode.toolkit.agent.domain.model.round2

class LinuxHostMetrics : HostMetricsSource
{
    private val prevCpu = AtomicReference<CpuCounters?>(null)
    private val prevNet = AtomicReference<NetCounters?>(null)
    private val prevDisk = AtomicReference<Map<String, DiskIoCounters>?>(null)
    private val prevIoAtNs = AtomicReference(0L)

    override fun snapshot(): HostMetrics
    {
        val load1 = parseLoad1(readProc("loadavg").orEmpty())
        val ncpu = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val loadPct = round2(load1 / ncpu * 100.0)
        val (totalKb, availKb) = parseMemKb(readProc("meminfo").orEmpty())
        val usedKb = (totalKb - availKb).coerceAtLeast(0)
        val memPct = if (totalKb > 0) usedKb.toDouble() / totalKb * 100.0 else 0.0
        val cpuPct = cpuSample(readProc("stat").orEmpty())
        val capacity = disksFromMounts(readProc("mounts").orEmpty(), ::statMount)
        val io = ioSample(readProc("net/dev").orEmpty(), readProc("diskstats").orEmpty())
        val disks = mergeDiskCapacityWithIo(capacity, io.byDisk)
        return HostMetrics(
            cpuPct = round2(cpuPct),
            load1 = round2(load1),
            loadPct = loadPct,
            ncpu = ncpu,
            memPct = round2(memPct),
            memUsedMb = round2(usedKb / 1024.0),
            memTotalMb = round2(totalKb / 1024.0),
            disks = disks,
            os = osName(),
            arch = archName(),
            netRxMbps = io.netRxMbps,
            netTxMbps = io.netTxMbps,
            diskReadIops = io.diskReadIops,
            diskWriteIops = io.diskWriteIops,
            diskReadMbS = io.diskReadMbS,
            diskWriteMbS = io.diskWriteMbS,
            diskUtilPct = io.diskUtilPct,
            diskBusy = io.diskBusy,
        )
    }

    private fun cpuSample(stat: String): Double
    {
        val now = parseCpuCounters(stat) ?: return 0.0
        val prev = prevCpu.getAndSet(now)
        if (prev == null)
        {
            return 0.0
        }
        return cpuBusyPct(prev, now)
    }

    private fun ioSample(netRaw: String, diskRaw: String) =
        run {
            val nowNet = parseNetCounters(netRaw)
            val nowDisk = parseDiskIoCounters(diskRaw)
            val nowNs = System.nanoTime()
            val prevN = prevNet.getAndSet(nowNet)
            val prevD = prevDisk.getAndSet(nowDisk)
            val prevAt = prevIoAtNs.getAndSet(nowNs)
            val dtSec = if (prevAt > 0) (nowNs - prevAt) / 1_000_000_000.0 else 0.0
            hostIoRates(prevN, nowNet, prevD, nowDisk, dtSec)
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
            // /proc entries are not always "regular files" to NIO; readable is enough.
            val file = Path.of(root).resolve(name)
            if (Files.isReadable(file))
            {
                return try
                {
                    Files.readString(file)
                }
                catch (_: Exception)
                {
                    null
                }
            }
        }
        return null
    }

    private fun osName(): String
    {
        val raw = System.getProperty("os.name")?.lowercase().orEmpty()
        return when
        {
            "linux" in raw -> "linux"
            "mac" in raw -> "darwin"
            "win" in raw -> "windows"
            else -> raw.ifBlank { "unknown" }
        }
    }

    private fun archName(): String
    {
        val raw = System.getProperty("os.arch")?.lowercase().orEmpty()
        return when
        {
            "aarch64" in raw || "arm64" in raw -> "arm64"
            "amd64" in raw || "x86_64" in raw -> "amd64"
            else -> raw.ifBlank { "unknown" }
        }
    }
}
