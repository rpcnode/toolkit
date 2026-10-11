package rpcnode.toolkit.chains.xrpl.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XrplHostBinariesTest
{
    private val elf = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0)

    @Test
    fun picks_usr_bin_not_the_logrotate_file_of_the_same_name()
    {
        val root = Files.createTempDirectory("deb-xrpld")
        // dpkg-deb -x layout of the Ripple package: etc/ comes first in a walk
        Files.createDirectories(root.resolve("etc/logrotate.d"))
        Files.writeString(root.resolve("etc/logrotate.d/xrpld"), "/var/log/xrpld/*.log {\n  daily\n}\n")
        Files.createDirectories(root.resolve("usr/bin"))
        Files.write(root.resolve("usr/bin/xrpld"), elf)

        assertEquals(root.resolve("usr/bin/xrpld"), XrplHostBinaries.findBinary(root))
    }

    @Test
    fun no_elf_named_xrpld_means_not_found()
    {
        val root = Files.createTempDirectory("deb-xrpld")
        Files.createDirectories(root.resolve("etc/logrotate.d"))
        Files.writeString(root.resolve("etc/logrotate.d/xrpld"), "daily\n")
        assertNull(XrplHostBinaries.findBinary(root))
    }

    @Test
    fun elf_check()
    {
        val dir = Files.createTempDirectory("bin")
        val bin = dir.resolve("a").also { Files.write(it, elf) }
        val text = dir.resolve("b").also { Files.writeString(it, "/var/log/xrpld/*.log {") }
        assertTrue(XrplHostBinaries.isElf(bin))
        assertFalse(XrplHostBinaries.isElf(text))
    }
}
