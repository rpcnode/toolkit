package rpcnode.toolkit.chains.arb.infrastructure.docker

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class ArbNitroDockerPackTest
{
    @Test
    fun applyLayer_extracts_nitro_and_machines()
    {
        val stage = Files.createTempDirectory("nitro-stage-")
        val layerRoot = Files.createTempDirectory("nitro-layer-")
        try
        {
            Files.createDirectories(layerRoot.resolve("usr/local/bin"))
            Files.writeString(layerRoot.resolve("usr/local/bin/nitro"), "#!/bin/sh\n")
            Files.createDirectories(layerRoot.resolve("home/user/nitro-legacy/machines"))
            Files.writeString(layerRoot.resolve("home/user/nitro-legacy/machines/a"), "m")
            Files.createDirectories(layerRoot.resolve("home/user/target/machines"))
            Files.writeString(layerRoot.resolve("home/user/target/machines/b"), "m")
            // Image ships 555 trees — extract must still allow relocate.
            layerRoot.resolve("home/user/target/machines").toFile().setReadOnly()
            Files.createDirectories(layerRoot.resolve("etc"))
            Files.writeString(layerRoot.resolve("etc/noise"), "x")
            val layer = stage.resolve("layer.tar.gz")
            val tar = ProcessBuilder(
                "tar", "-czf", layer.toAbsolutePath().toString(),
                "-C", layerRoot.toAbsolutePath().toString(),
                "usr", "home", "etc",
            )
            tar.redirectErrorStream(true)
            val p = tar.start()
            check(p.waitFor() == 0) { p.inputStream.bufferedReader().readText() }

            ArbNitroDockerPack.applyLayer(layer, stage)
            ArbNitroDockerPack.normalizeLayout(stage)
            assertTrue(Files.isRegularFile(stage.resolve("bin/nitro")))
            assertTrue(Files.isRegularFile(stage.resolve("nitro-legacy/machines/a")))
            assertTrue(Files.isRegularFile(stage.resolve("target/machines/b")))
            assertTrue(!Files.exists(stage.resolve("etc/noise")))
        }
        finally
        {
            stage.toFile().deleteRecursively()
            layerRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun applyLayer_whiteouts_before_extract_keeps_new_machines()
    {
        val stage = Files.createTempDirectory("nitro-wh-")
        val layerRoot = Files.createTempDirectory("nitro-wh-layer-")
        try
        {
            // Lower layer content already in stage (will be cleared by opaque whiteout).
            Files.createDirectories(stage.resolve("home/user/target/machines"))
            Files.writeString(stage.resolve("home/user/target/machines/old"), "old")

            Files.createDirectories(layerRoot.resolve("home/user/target"))
            Files.writeString(layerRoot.resolve("home/user/target/.wh..wh..opq"), "")
            Files.createDirectories(layerRoot.resolve("home/user/target/machines"))
            Files.writeString(layerRoot.resolve("home/user/target/machines/new"), "new")
            val layer = stage.resolve("layer.tar.gz")
            val tar = ProcessBuilder(
                "tar", "-czf", layer.toAbsolutePath().toString(),
                "-C", layerRoot.toAbsolutePath().toString(),
                "home",
            )
            tar.redirectErrorStream(true)
            val p = tar.start()
            check(p.waitFor() == 0) { p.inputStream.bufferedReader().readText() }

            ArbNitroDockerPack.applyLayer(layer, stage)
            assertTrue(Files.isRegularFile(stage.resolve("home/user/target/machines/new")))
            assertTrue(!Files.exists(stage.resolve("home/user/target/machines/old")))
        }
        finally
        {
            stage.toFile().deleteRecursively()
            layerRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun normalizeLayout_relocates_readonly_machines_tree()
    {
        val stage = Files.createTempDirectory("nitro-ro-")
        try
        {
            val nested = stage.resolve("home/user/target/machines")
            Files.createDirectories(nested)
            Files.writeString(nested.resolve("x"), "1")
            nested.toFile().setWritable(false)
            nested.resolve("x").toFile().setWritable(false)
            ArbNitroDockerPack.normalizeLayout(stage)
            assertTrue(Files.isRegularFile(stage.resolve("target/machines/x")))
            assertTrue(!Files.exists(nested))
        }
        finally
        {
            ProcessBuilder("chmod", "-R", "u+w", stage.toAbsolutePath().toString()).start().waitFor()
            stage.toFile().deleteRecursively()
        }
    }
}
