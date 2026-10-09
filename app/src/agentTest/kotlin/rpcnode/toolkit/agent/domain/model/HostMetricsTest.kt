package rpcnode.toolkit.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostMetricsTest
{
    @Test
    fun parse_load_mem_cpu()
    {
        assertEquals(0.42, parseLoad1("0.42 0.51 0.60 1/200 1234"))
        val (total, avail) = parseMemKb("MemTotal:       16384000 kB\nMemAvailable:    8192000 kB\n")
        assertEquals(16_384_000L, total)
        assertEquals(8_192_000L, avail)
        val a = parseCpuCounters("cpu  10 0 10 80 0 0 0\ncpu0 1 0 1 8\n")!!
        val b = parseCpuCounters("cpu  20 0 10 90 0 0 0\n")!!
        assertEquals(50.0, cpuBusyPct(a, b))
    }

    @Test
    fun disks_from_mounts_skips_tmpfs_and_keeps_whole_disks()
    {
        val mounts = """
            /dev/nvme0n1p2 / ext4 rw 0 0
            /dev/nvme1n1 /data/nvme1 ext4 rw 0 0
            tmpfs /run tmpfs rw 0 0
            /dev/sda1 /boot ext4 rw 0 0
            /dev/sdb1 /mnt/hdd ext4 rw 0 0
        """.trimIndent()
        val space = mapOf(
            "/" to (500L * G to 100L * G),
            "/data/nvme1" to (2000L * G to 1500L * G),
            "/mnt/hdd" to (4000L * G to 3000L * G),
        )
        val disks = disksFromMounts(mounts) { space[it] }
        assertEquals(3, disks.size)
        assertEquals("nvme0n1", disks[0].name)
        assertEquals("/", disks[0].mount)
        assertEquals(100.0, disks[0].freeGb, 0.01)
        assertEquals(500.0, disks[0].totalGb, 0.01)
        assertEquals("nvme1n1", disks[1].name)
        assertEquals("sdb", disks[2].name)
        assertTrue(disks.none { it.mount == "/boot" })
    }

    @Test
    fun host_metrics_sums_disk_space()
    {
        val m = HostMetrics(
            cpuPct = 10.0,
            load1 = 1.0,
            loadPct = 25.0,
            ncpu = 4,
            memPct = 50.0,
            memUsedMb = 8000.0,
            memTotalMb = 16000.0,
            disks = listOf(
                HostDisk("nvme0n1", "/", 100.0, 500.0, 80.0),
                HostDisk("nvme1n1", "/data", 1500.0, 2000.0, 25.0),
            ),
            os = "linux",
            arch = "amd64",
        )
        assertEquals(2500.0, m.diskTotalGb)
        assertEquals(900.0, m.diskUsedGb)
    }
}

private const val G = 1024L * 1024L * 1024L
