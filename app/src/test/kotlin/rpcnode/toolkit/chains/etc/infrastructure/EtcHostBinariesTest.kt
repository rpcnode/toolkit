package rpcnode.toolkit.chains.etc.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EtcHostBinariesTest
{
    private fun zip(dir: Path, name: String, vararg entries: Pair<String, ByteArray>)
    {
        ZipOutputStream(Files.newOutputStream(dir.resolve(name))).use { out ->
            for ((n, bytes) in entries)
            {
                out.putNextEntry(ZipEntry(n))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    @Test
    fun unzips_geth_once_and_again_when_the_zip_changes()
    {
        val dir = Files.createTempDirectory("etc-bin")
        zip(dir, "core-geth-linux.zip", "geth" to byteArrayOf(1, 2, 3, 4), "COPYING" to byteArrayOf(9))
        val first = EtcHostBinaries.ensure(dir) as EtcHostBinaries.Result.Ok
        assertEquals(dir.resolve("bin/geth").toAbsolutePath().normalize(), first.node)
        assertTrue(Files.isExecutable(first.node))
        assertEquals(listOf<Byte>(1, 2, 3, 4), Files.readAllBytes(first.node).toList())
        val stamp = Files.getLastModifiedTime(first.node)

        EtcHostBinaries.ensure(dir)
        assertEquals(stamp, Files.getLastModifiedTime(first.node), "unchanged zip must not be re-extracted")

        zip(dir, "core-geth-linux.zip", "geth" to byteArrayOf(5, 6, 7, 8, 9))
        val again = EtcHostBinaries.ensure(dir) as EtcHostBinaries.Result.Ok
        assertEquals(listOf<Byte>(5, 6, 7, 8, 9), Files.readAllBytes(again.node).toList())
    }

    @Test
    fun missing_zip_and_unsafe_entries_fail_cleanly()
    {
        val dir = Files.createTempDirectory("etc-bin-empty")
        val missing = EtcHostBinaries.ensure(dir) as EtcHostBinaries.Result.Failed
        assertTrue("core-geth zip missing" in missing.detail)

        zip(dir, "core-geth-linux.zip", "../geth" to byteArrayOf(1), "COPYING" to byteArrayOf(2))
        val unsafe = EtcHostBinaries.ensure(dir) as EtcHostBinaries.Result.Failed
        assertTrue("no `geth` binary" in unsafe.detail, unsafe.detail)
        assertTrue(!Files.exists(dir.resolve("bin/geth")))
    }
}
