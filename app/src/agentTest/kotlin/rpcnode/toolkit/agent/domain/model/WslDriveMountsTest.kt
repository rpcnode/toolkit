package rpcnode.toolkit.agent.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class WslDriveMountsTest
{
    private val gib = 1024L * 1024 * 1024

    @Test
    fun lists_windows_drives_and_skips_everything_else()
    {
        val raw = """
            /dev/sdd / ext4 rw 0 0
            drivers /usr/lib/wsl/drivers 9p ro 0 0
            C:\134 /mnt/c 9p rw,noatime,aname=drvfs 0 0
            J:\134 /mnt/j 9p rw,noatime,aname=drvfs 0 0
            tmpfs /run tmpfs rw 0 0
        """.trimIndent()
        val space = mapOf("/mnt/c" to (447 * gib to 70 * gib), "/mnt/j" to (954 * gib to 911 * gib))
        val mounts = windowsDriveMounts(raw) { space[it] }
        assertEquals(listOf("/mnt/c", "/mnt/j"), mounts.map { it.target })
        assertEquals("J:\\", mounts[1].source)
        assertEquals("9p", mounts[1].fstype)
        assertEquals("win-j", mounts[1].diskName)
        assertEquals(954 * gib, mounts[1].sizeBytes)
    }

    @Test
    fun skips_drives_whose_space_cannot_be_read()
    {
        val raw = """D:\134 /mnt/d 9p rw 0 0"""
        assertEquals(emptyList(), windowsDriveMounts(raw) { null })
    }

    @Test
    fun root_disk_mounted_at_wslg_distro_is_shown_as_root()
    {
        val fixed = fixWslRootMount(listOf(MountPoint(target = "/mnt/wslg/distro", diskName = "sdd")))
        assertEquals("/", fixed.single().target)
        val keep = listOf(MountPoint(target = "/"), MountPoint(target = "/mnt/wslg/distro"))
        assertEquals(keep, fixWslRootMount(keep))
    }
}
