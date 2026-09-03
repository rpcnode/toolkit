package rpcnode.toolkit.agent.infrastructure.proc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LsblkHostDiskParserTest
{
    @Test
    fun parse_lists_disks_mounts_and_unused() {
        val raw = """
            {
              "blockdevices": [
                {
                  "name": "nvme0n1",
                  "type": "disk",
                  "path": "/dev/nvme0n1",
                  "size": 1000000000,
                  "tran": "nvme",
                  "rota": false,
                  "children": [
                    {
                      "name": "nvme0n1p1",
                      "type": "part",
                      "path": "/dev/nvme0n1p1",
                      "size": 500000000,
                      "mountpoint": "/",
                      "fstype": "ext4",
                      "fsavail": 100000000,
                      "fsuse%": 10.0
                    }
                  ]
                },
                {
                  "name": "nvme1n1",
                  "type": "disk",
                  "path": "/dev/nvme1n1",
                  "size": 2000000000,
                  "tran": "nvme",
                  "rota": false,
                  "children": [
                    {
                      "name": "nvme1n1p1",
                      "type": "part",
                      "path": "/dev/nvme1n1p1",
                      "size": 2000000000,
                      "mountpoint": "/mnt/data1",
                      "fstype": "xfs",
                      "fsavail": 1500000000,
                      "fsuse%": 25.0
                    }
                  ]
                },
                {
                  "name": "nvme2n1",
                  "type": "disk",
                  "path": "/dev/nvme2n1",
                  "size": 500000000000,
                  "tran": "nvme",
                  "rota": false
                }
              ]
            }
        """.trimIndent()

        val inv = LsblkHostDiskParser.parse(raw)
        assertEquals(3, inv.disks.size)
        assertEquals("nvme0n1", inv.disks[0].name)
        assertTrue(inv.disks[0].preferred)
        assertEquals(2, inv.mounts.size)
        assertEquals("/mnt/data1", inv.mounts.first { it.target == "/mnt/data1" }.target)
        assertEquals(1, inv.unused.size)
        assertEquals("nvme2n1", inv.unused[0].name)
    }

    @Test
    fun parse_omits_linux_raid_member_disks_and_mounts() {
        val raw = """
            {
              "blockdevices": [
                {
                  "name": "nvme2n1",
                  "type": "disk",
                  "path": "/dev/nvme2n1",
                  "size": 2000000000,
                  "tran": "nvme",
                  "rota": false,
                  "children": [
                    {
                      "name": "nvme2n1p1",
                      "type": "part",
                      "path": "/dev/nvme2n1p1",
                      "size": 2000000000,
                      "fstype": "linux_raid_member"
                    }
                  ]
                },
                {
                  "name": "nvme3n1",
                  "type": "disk",
                  "path": "/dev/nvme3n1",
                  "size": 2000000000,
                  "tran": "nvme",
                  "rota": false,
                  "children": [
                    {
                      "name": "nvme3n1p1",
                      "type": "part",
                      "path": "/dev/nvme3n1p1",
                      "size": 2000000000,
                      "mountpoint": "/mnt/data2",
                      "fstype": "xfs",
                      "fsavail": 1500000000,
                      "fsuse%": 10.0
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val inv = LsblkHostDiskParser.parse(raw)
        assertEquals(listOf("nvme3n1"), inv.disks.map { it.name })
        assertEquals(1, inv.mounts.size)
        assertEquals("/mnt/data2", inv.mounts[0].target)
        assertTrue(inv.unused.isEmpty())
        assertTrue(inv.disks.none { it.fstype == "linux_raid_member" })
    }
}
