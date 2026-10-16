package rpcnode.toolkit.chains.nonevm.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NonEvmHostBinariesTest
{
    private fun run(vararg cmd: String, dir: Path)
    {
        val p = ProcessBuilder(*cmd).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), out)
    }

    @Test
    fun raw_binaries_are_installed_and_refreshed_when_the_synced_file_is_newer()
    {
        val dir = createTempDirectory("raw")
        dir.resolve("polkadot").writeText("v1")
        dir.resolve("polkadot-prepare-worker").writeText("w1")
        val r = NonEvmHostBinaries.ensure(dir, listOf("polkadot", "polkadot-prepare-worker"), NonEvmHostBinaries.Source.Raw)
        assertIs<NonEvmHostBinaries.Result.Ok>(r)
        assertEquals("v1", Files.readString(dir.resolve("bin/polkadot")))
        assertTrue(Files.isExecutable(dir.resolve("bin/polkadot")))
        assertTrue(Files.exists(dir.resolve("polkadot")), "the synced file stays for client update / rollback")
        dir.resolve("polkadot").writeText("v2")
        Files.setLastModifiedTime(dir.resolve("polkadot"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5_000))
        NonEvmHostBinaries.ensure(dir, listOf("polkadot"), NonEvmHostBinaries.Source.Raw)
        assertEquals("v2", Files.readString(dir.resolve("bin/polkadot")))
    }

    @Test
    fun a_raw_binary_saved_per_architecture_is_found_under_its_plain_name()
    {
        val dir = createTempDirectory("arch")
        dir.resolve("neard-aarch64").writeText("n")
        // polkadot-prepare-worker must not be mistaken for an arch variant of polkadot
        dir.resolve("polkadot-prepare-worker").writeText("w")
        assertIs<NonEvmHostBinaries.Result.Ok>(NonEvmHostBinaries.ensure(dir, listOf("neard"), NonEvmHostBinaries.Source.Raw))
        assertEquals("n", Files.readString(dir.resolve("bin/neard")))
        assertIs<NonEvmHostBinaries.Result.Failed>(NonEvmHostBinaries.ensure(dir, listOf("polkadot"), NonEvmHostBinaries.Source.Raw))
    }

    @Test
    fun zip_and_tar_members_are_found_anywhere_in_the_archive()
    {
        val work = createTempDirectory("ar")
        Files.createDirectories(work.resolve("fuel-core-0.48.3-x86_64"))
        work.resolve("fuel-core-0.48.3-x86_64/fuel-core").writeText("#!/bin/sh\nexit 0\n")
        work.resolve("fuel-core-0.48.3-x86_64/fuel-core-keygen").writeText("k")
        work.resolve("fuel-core-0.48.3-x86_64/fuel-core-wasm-executor.wasm").writeText("w")
        val node = createTempDirectory("node")
        run("tar", "-czf", node.resolve("fuel-core-linux-x86_64.tar.gz").toString(), "-C", work.toString(), "fuel-core-0.48.3-x86_64", dir = work)
        val tgz = NonEvmHostBinaries.ensure(
            node, listOf("fuel-core", "fuel-core-keygen"),
            NonEvmHostBinaries.Source.Archive("fuel-core-linux-*.tar.gz", NonEvmHostBinaries.ArchiveKind.TAR_GZ), extra = listOf("fuel-core-wasm-executor.wasm"),
        )
        assertIs<NonEvmHostBinaries.Result.Ok>(tgz)
        assertTrue(Files.isExecutable(node.resolve("bin/fuel-core")) && Files.exists(node.resolve("bin/fuel-core-wasm-executor.wasm")))
        assertEquals("0", NonEvmHostBinaries.versionOutput(node.resolve("bin/fuel-core"))?.let { "0" })

        val zipDir = createTempDirectory("zipsrc")
        zipDir.resolve("stacks-node").writeText("s")
        zipDir.resolve("stacks-signer").writeText("g")
        val node2 = createTempDirectory("node2")
        run("zip", "-q", node2.resolve("stacks-core-linux-x86_64.zip").toString(), "stacks-node", "stacks-signer", dir = zipDir)
        val z = NonEvmHostBinaries.ensure(node2, listOf("stacks-node"), NonEvmHostBinaries.Source.Archive("stacks-core-linux-*.zip", NonEvmHostBinaries.ArchiveKind.ZIP))
        assertIs<NonEvmHostBinaries.Result.Ok>(z)
        assertEquals("s", Files.readString(node2.resolve("bin/stacks-node")))
        val missing = NonEvmHostBinaries.ensure(node2, listOf("nope"), NonEvmHostBinaries.Source.Archive("stacks-core-linux-*.zip", NonEvmHostBinaries.ArchiveKind.ZIP))
        assertIs<NonEvmHostBinaries.Result.Failed>(missing)
    }
}
