package rpcnode.toolkit.chains.arb.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArbHostBinariesTest
{
    @Test
    fun ensure_fails_when_nitro_missing()
    {
        val dir = Files.createTempDirectory("arb-bin-")
        val result = ArbHostBinaries.ensure(dir)
        assertIs<ArbHostBinaries.Result.Failed>(result)
        assertTrue(result.detail.contains("nitro missing"))
        assertTrue(result.detail.contains("sync arb clients"))
    }

    @Test
    fun ensure_ok_when_bin_and_wasm_present()
    {
        val dir = Files.createTempDirectory("arb-bin-")
        val bin = dir.resolve("bin")
        Files.createDirectories(bin)
        val nitro = bin.resolve("nitro")
        Files.writeString(nitro, "#!/bin/sh\n")
        nitro.toFile().setExecutable(true)
        Files.createDirectories(dir.resolve("nitro-legacy/machines"))
        Files.createDirectories(dir.resolve("target/machines"))
        val result = ArbHostBinaries.ensure(dir)
        assertIs<ArbHostBinaries.Result.Ok>(result)
        assertTrue(result.bins.wasmRoots.contains("nitro-legacy/machines"))
    }

    @Test
    fun ensure_extracts_client_tarball()
    {
        val dir = Files.createTempDirectory("arb-bin-")
        val stage = Files.createTempDirectory("arb-stage-")
        Files.createDirectories(stage.resolve("bin"))
        Files.writeString(stage.resolve("bin/nitro"), "#!/bin/sh\n")
        stage.resolve("bin/nitro").toFile().setExecutable(true)
        Files.createDirectories(stage.resolve("nitro-legacy/machines"))
        Files.createDirectories(stage.resolve("target/machines"))
        Files.writeString(stage.resolve("nitro-legacy/machines/x"), "m")
        Files.writeString(stage.resolve("target/machines/y"), "m")
        val archive = dir.resolve("nitro-v3.11.3-x86_64-linux.tar.gz")
        val pb = ProcessBuilder(
            "tar", "-czf", archive.toAbsolutePath().toString(),
            "-C", stage.toAbsolutePath().toString(),
            "bin", "nitro-legacy", "target",
        )
        pb.redirectErrorStream(true)
        val p = pb.start()
        check(p.waitFor() == 0) { p.inputStream.bufferedReader().readText() }

        val result = ArbHostBinaries.ensure(dir)
        assertIs<ArbHostBinaries.Result.Ok>(result)
        assertTrue(Files.isRegularFile(dir.resolve("bin/nitro")))
        assertTrue(Files.isDirectory(dir.resolve("nitro-legacy/machines")))
    }
}
