package rpcnode.toolkit.chains.starknet.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StarknetHostBinariesTest
{
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Same layout as juno-v0.16.8-linux-amd64.zip: the ELF and `<sha256>  <name>` (VERIFIED against the real release). */
    private fun releaseZip(dir: Path, content: String, sumOverride: String? = null, name: String = "juno-x86_64-linux.zip"): Path
    {
        val bytes = content.toByteArray()
        val zip = dir.resolve(name)
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry("juno-v0.16.8-linux-amd64"))
            out.write(bytes)
            out.closeEntry()
            out.putNextEntry(ZipEntry("juno-v0.16.8-linux-amd64.sha256"))
            out.write("${sumOverride ?: sha(bytes)}  juno-v0.16.8-linux-amd64\n".toByteArray())
            out.closeEntry()
        }
        return zip
    }

    @Test
    fun unpacks_the_versioned_elf_to_bin_juno_and_marks_it_executable()
    {
        val dir = Files.createTempDirectory("juno-bin-")
        releaseZip(dir, "ELF-v1")
        val result = StarknetHostBinaries.ensure(dir)
        assertIs<StarknetHostBinaries.Result.Ok>(result)
        assertEquals(dir.toRealPath().resolve("bin/juno"), result.juno.toRealPath())
        assertEquals("ELF-v1", Files.readString(result.juno))
        assertTrue(Files.isExecutable(result.juno))
        assertTrue(Files.notExists(dir.resolve("bin/juno.tmp")))
    }

    @Test
    fun a_checksum_mismatch_inside_the_zip_is_refused_and_leaves_no_binary()
    {
        val dir = Files.createTempDirectory("juno-bin-")
        releaseZip(dir, "ELF-v1", sumOverride = "0".repeat(64))
        val result = StarknetHostBinaries.ensure(dir)
        assertIs<StarknetHostBinaries.Result.Failed>(result)
        assertTrue(result.detail.contains("checksum mismatch"), result.detail)
        assertTrue(Files.notExists(dir.resolve("bin/juno")))
    }

    @Test
    fun a_newer_zip_after_a_client_update_replaces_the_binary_but_an_older_one_does_not()
    {
        val dir = Files.createTempDirectory("juno-bin-")
        val zip = releaseZip(dir, "ELF-v1")
        assertIs<StarknetHostBinaries.Result.Ok>(StarknetHostBinaries.ensure(dir))
        val bin = dir.resolve("bin/juno")
        Files.setLastModifiedTime(bin, FileTime.fromMillis(1_000_000_000_000L))
        Files.setLastModifiedTime(zip, FileTime.fromMillis(1_000_000_000_000L - 5_000))
        releaseZip(dir, "ELF-v2")
        Files.setLastModifiedTime(zip, FileTime.fromMillis(1_000_000_000_000L - 5_000))
        assertIs<StarknetHostBinaries.Result.Ok>(StarknetHostBinaries.ensure(dir))
        assertEquals("ELF-v1", Files.readString(bin), "zip older than the binary: nothing to do")
        Files.setLastModifiedTime(zip, FileTime.fromMillis(1_000_000_000_000L + 5_000))
        assertIs<StarknetHostBinaries.Result.Ok>(StarknetHostBinaries.ensure(dir))
        assertEquals("ELF-v2", Files.readString(bin))
    }

    @Test
    fun missing_zip_and_missing_binary_say_which_clients_to_sync()
    {
        val result = StarknetHostBinaries.ensure(Files.createTempDirectory("juno-bin-"))
        assertIs<StarknetHostBinaries.Result.Failed>(result)
        assertTrue(result.detail.contains("sync starknet clients"), result.detail)
    }

    @Test
    fun an_existing_binary_without_a_zip_is_used_as_is()
    {
        val dir = Files.createTempDirectory("juno-bin-")
        Files.createDirectories(dir.resolve("bin"))
        Files.writeString(dir.resolve("bin/juno"), "x")
        assertIs<StarknetHostBinaries.Result.Ok>(StarknetHostBinaries.ensure(dir))
    }

    @Test
    fun only_the_program_entry_counts_not_its_checksum_file()
    {
        assertTrue(StarknetHostBinaries.isBinaryEntry("juno-v0.16.8-linux-arm64"))
        assertTrue(StarknetHostBinaries.isBinaryEntry("dist/juno"))
        assertFalse(StarknetHostBinaries.isBinaryEntry("juno-v0.16.8-linux-arm64.sha256"))
        assertFalse(StarknetHostBinaries.isBinaryEntry("README.md"))
    }

    @Test
    fun parent_env_file_is_owner_only_and_only_ws_urls_are_accepted()
    {
        val dir = Files.createTempDirectory("juno-env-")
        val file = StarknetParentChain.writeEnvFile(dir, "wss://eth.example/ws/v3/SECRETKEY")
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
        assertEquals("wss://eth.example/ws/v3/SECRETKEY", StarknetParentChain.readEnvFile(dir))
        assertNull(StarknetParentChain.readEnvFile(Files.createTempDirectory("juno-none-")))
        assertNull(StarknetParentChain.validate("wss://ethereum-rpc.publicnode.com"))
        assertNull(StarknetParentChain.validate("ws://127.0.0.1:8546"))
        for (bad in listOf("", "https://eth.example", "http://127.0.0.1:8545", "wss://x y", "wss://x\"", "wss://x\nA=b", "wss://x/\$Y", "wss://x/%d"))
        {
            assertNotNull(StarknetParentChain.validate(bad), "should reject <$bad>")
        }
        val mainnet = StarknetChain.lookup("mainnet")
        assertEquals("wss://ethereum-rpc.publicnode.com", StarknetParentChain.resolve(mainnet, null))
        assertEquals("wss://mine", StarknetParentChain.resolve(mainnet, " wss://mine "))
    }
}
