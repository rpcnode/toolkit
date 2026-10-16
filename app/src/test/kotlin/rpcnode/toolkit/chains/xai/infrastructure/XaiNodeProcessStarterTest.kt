package rpcnode.toolkit.chains.xai.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.xai.infrastructure.proc.XaiNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

class XaiNodeProcessStarterTest
{
    private val launch = NodeLaunchSpec(kind = "binary", entry = "bin/nitro", args = listOf("--toolkit-l1-rpc=https://arb.example/v2/KEY"))

    @Test
    fun fails_before_touching_anything_when_the_nitro_tarball_was_not_synced()
    {
        val dir = Files.createTempDirectory("xai-start-")
        val result = XaiNodeProcessStarter().start("n1", "xai", "mainnet", dir.toString(), launch)
        assertIs<HostNodeStartResult.Failed>(result)
        assertTrue(result.detail.contains("nitro missing"), result.detail)
        assertTrue(Files.notExists(dir.resolve(".toolkit/xai-parent.env")), "no secret file for a start that cannot happen")
    }

    @Test
    fun refuses_a_parent_url_that_does_not_fit_the_env_file()
    {
        val dir = Files.createTempDirectory("xai-start-")
        Files.createDirectories(dir.resolve("bin"))
        Files.writeString(dir.resolve("bin/nitro"), "#!/bin/sh\n")
        Files.createDirectories(dir.resolve("nitro-legacy/machines"))
        Files.createDirectories(dir.resolve("target/machines"))
        val result = XaiNodeProcessStarter().start(
            "n1", "xai", "mainnet", dir.toString(),
            launch.copy(args = listOf("--toolkit-l1-rpc=https://x/\"; touch /tmp/pwned")),
        )
        assertIs<HostNodeStartResult.Failed>(result)
        assertTrue(result.detail.contains("parent RPC"), result.detail)
        assertTrue(Files.notExists(dir.resolve(".toolkit/xai-parent.env")))
    }
}
