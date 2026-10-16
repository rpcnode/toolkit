package rpcnode.toolkit.chains.evmfamily.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** A real `.deb` through `dpkg-deb --fsys-tarfile`: only runs where dpkg-deb, ar and tar exist (the Ubuntu hosts). */
class EvmHostBinariesDebTest
{
    private fun has(tool: String): Boolean =
        runCatching { ProcessBuilder("which", tool).redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)

    private fun sh(dir: Path, vararg cmd: String)
    {
        val p = ProcessBuilder(*cmd).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")}: $out" }
    }

    private fun deb(root: Path): Path
    {
        val work = Files.createTempDirectory("deb-build")
        val data = work.resolve("data")
        Files.createDirectories(data.resolve("usr/local/bin"))
        Files.createDirectories(data.resolve("usr/local/lib"))
        Files.createDirectories(data.resolve("usr/lib/systemd/system"))
        Files.writeString(data.resolve("usr/local/bin/monad"), "#!/bin/sh\necho monad\n")
        Files.writeString(data.resolve("usr/local/bin/monad-rpc"), "#!/bin/sh\necho rpc\n")
        Files.writeString(data.resolve("usr/local/bin/monad-indexer"), "unwanted")
        Files.writeString(data.resolve("usr/local/lib/libmonad_execution.so"), "lib")
        Files.writeString(data.resolve("usr/lib/systemd/system/monad-bft.service"), "unit")
        val control = work.resolve("control")
        Files.createDirectories(control)
        Files.writeString(control.resolve("control"), "Package: monad\nVersion: 0.0.1\nArchitecture: amd64\nMaintainer: t <t@t>\nDescription: t\n")
        Files.writeString(work.resolve("debian-binary"), "2.0\n")
        sh(work, "tar", "-czf", "control.tar.gz", "-C", "control", ".")
        sh(work, "tar", "-czf", "data.tar.gz", "-C", "data", ".")
        val out = root.resolve("monad-amd64.deb")
        sh(work, "ar", "rc", out.toString(), "debian-binary", "control.tar.gz", "data.tar.gz")
        return out
    }

    @Test
    fun only_the_named_binaries_and_all_libs_are_taken_from_the_package()
    {
        assumeTrue(has("dpkg-deb") && has("ar") && has("tar"), "dpkg-deb, ar and tar are needed")
        val root = Files.createTempDirectory("deb-node")
        deb(root)
        val binaries = listOf("monad", "monad-rpc").map { EvmBinary(it, "monad-amd64.deb", EvmBinary.Kind.DEB_BUNDLE, member = it) }
        val result = EvmHostBinaries.ensure(root, binaries)
        val ok = assertIs<EvmHostBinaries.Result.Ok>(result)
        assertEquals(setOf("monad", "monad-rpc"), ok.bins.keys)
        assertTrue(Files.isExecutable(root.resolve("bin/monad")) && Files.isExecutable(root.resolve("bin/monad-rpc")))
        assertTrue(!Files.exists(root.resolve("bin/monad-indexer")), "binaries that were not asked for stay in the package")
        assertTrue(Files.isRegularFile(root.resolve("lib/libmonad_execution.so")))
        assertTrue(Files.list(root).use { s -> s.noneMatch { it.fileName.toString().startsWith(".deb-") } }, "no temp dir left behind")
        // second run: nothing to do, and with the package gone the installed files are enough
        Files.delete(root.resolve("monad-amd64.deb"))
        assertIs<EvmHostBinaries.Result.Ok>(EvmHostBinaries.ensure(root, binaries))
    }

    @Test
    fun a_missing_package_without_installed_binaries_says_to_sync_the_clients()
    {
        val root = Files.createTempDirectory("deb-empty")
        val r = EvmHostBinaries.ensure(root, listOf(EvmBinary("monad", "monad-amd64.deb", EvmBinary.Kind.DEB_BUNDLE, member = "monad")))
        assertTrue(assertIs<EvmHostBinaries.Result.Failed>(r).detail.contains("sync clients"))
    }
}
