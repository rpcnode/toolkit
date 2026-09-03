package rpcnode.toolkit.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostIoTest
{
    @Test
    fun parse_net_dev_sums_physical_nics_and_skips_virtual()
    {
        val raw = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo: 1000       10    0    0    0     0          0         0     2000      20    0    0    0     0       0          0
              eth0: 125000000    1    0    0    0     0          0         0  250000000      2    0    0    0     0       0          0
            docker0: 999         1    0    0    0     0          0         0       999      1    0    0    0     0       0          0
              ens5: 50000000     1    0    0    0     0          0         0  100000000      1    0    0    0     0       0          0
        """.trimIndent()
        val c = parseNetCounters(raw)
        assertEquals(175_000_000L, c.rxBytes)
        assertEquals(350_000_000L, c.txBytes)
    }

    @Test
    fun parse_diskstats_keeps_whole_disks_only()
    {
        val raw = """
           8       0 sda 100 0 2000 10 50 0 800 20 0 30 0
           8       1 sda1 10 0 200 1 5 0 80 2 0 3 0
         259       0 nvme0n1 1000 0 20000 100 500 0 8000 200 0 300 0
         259       1 nvme0n1p1 100 0 2000 10 50 0 800 20 0 30 0
           7       0 loop0 1 0 8 1 0 0 0 0 0 0 0
        """.trimIndent()
        val disks = parseDiskIoCounters(raw)
        assertEquals(setOf("sda", "nvme0n1"), disks.keys)
        assertEquals(100L, disks["sda"]!!.reads)
        assertEquals(1000L, disks["nvme0n1"]!!.reads)
    }

    @Test
    fun host_io_rates_compute_mbps_iops_and_hottest_util()
    {
        val prevNet = NetCounters(rxBytes = 0, txBytes = 0)
        val nowNet = NetCounters(rxBytes = 12_500_000, txBytes = 25_000_000) // 100 Mbps rx, 200 Mbps tx over 1s
        val prevDisk = mapOf(
            "sda" to DiskIoCounters(reads = 0, writes = 0, readSectors = 0, writeSectors = 0, ioMs = 0),
            "nvme0n1" to DiskIoCounters(reads = 0, writes = 0, readSectors = 0, writeSectors = 0, ioMs = 0),
        )
        val nowDisk = mapOf(
            "sda" to DiskIoCounters(reads = 100, writes = 50, readSectors = 2000, writeSectors = 1000, ioMs = 200),
            "nvme0n1" to DiskIoCounters(reads = 400, writes = 100, readSectors = 8000, writeSectors = 2000, ioMs = 800),
        )
        val rates = hostIoRates(prevNet, nowNet, prevDisk, nowDisk, dtSec = 1.0)
        assertEquals(100.0, rates.netRxMbps, 0.01)
        assertEquals(200.0, rates.netTxMbps, 0.01)
        assertEquals(500.0, rates.diskReadIops, 0.01)
        assertEquals(150.0, rates.diskWriteIops, 0.01)
        assertEquals(80.0, rates.diskUtilPct, 0.01)
        assertEquals("nvme0n1", rates.diskBusy)
    }

    @Test
    fun first_sample_without_prev_is_zero()
    {
        val rates = hostIoRates(
            prevNet = null,
            nowNet = NetCounters(1, 1),
            prevDisk = null,
            nowDisk = mapOf("sda" to DiskIoCounters(1, 1, 1, 1, 1)),
            dtSec = 0.0,
        )
        assertEquals(0.0, rates.netRxMbps)
        assertEquals(0.0, rates.diskReadIops)
        assertEquals("", rates.diskBusy)
        assertTrue(rates.byDisk.isEmpty())
    }

    @Test
    fun merge_attaches_io_rates_onto_capacity_disks()
    {
        val capacity = listOf(HostDisk("nvme0n1", "/", 100.0, 500.0, 80.0))
        val rates = mapOf(
            "nvme0n1" to DiskIoRate(10.0, 5.0, 1.0, 0.5, 12.0),
            "sda" to DiskIoRate(1.0, 1.0, 0.1, 0.1, 3.0),
        )
        val merged = mergeDiskCapacityWithIo(capacity, rates)
        assertEquals(2, merged.size)
        assertEquals(10.0, merged[0].readIops)
        assertEquals(12.0, merged[0].utilPct)
        assertEquals("sda", merged[1].name)
        assertEquals(0.0, merged[1].totalGb)
    }
}
