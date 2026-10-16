package rpcnode.toolkit.chains.arc.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class ArcNodeStartTest
{
    @Test
    fun plan_encodes_env_snapshot_type_and_the_execution_role()
    {
        val plan = ArcNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.ARC,
                env = "mainnet",
                program = "arc-node",
                configFile = null,
                nodeDir = "/data/rpcnode/arc/mainnet/exec",
                installOptionsJson = """{"snapshot":"archive"}""",
                diskLayoutJson = """{"roles":[{"id":"execution","dir":"/data/rpcnode/arc/mainnet/exec"}]}""",
            ),
        )
        assertEquals(NetworkId.ARC, ArcNodeStart().networkId)
        assertEquals("bin/arc-node-execution", plan.launch.entry)
        assertEquals("arc-node-linux-*.tar.gz", plan.launch.extractArchiveGlob)
        assertEquals("eth_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertEquals("logs/arc.log", plan.launch.logFile)
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-mode=archive"))
        assertTrue(plan.launch.args.contains("--toolkit-execution=/data/rpcnode/arc/mainnet/exec"))
    }

    @Test
    fun plan_defaults_to_a_full_mainnet_node()
    {
        val plan = ArcNodeStart().plan(
            ChainNodeStartContext(network = NetworkId.ARC, env = "weird", program = "arc-node", configFile = null, nodeDir = "/n"),
        )
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-mode=full"))
        assertTrue(plan.launch.args.contains("--toolkit-execution=/n"))
    }
}

class ArcNodeModeTest
{
    @Test
    fun the_wizard_saves_the_node_type_as_snapshot_and_an_older_node_key_still_works()
    {
        assertEquals("archive", rpcnode.toolkit.chains.arc.infrastructure.ArcNodeMode.fromJson("""{"snapshot":"archive"}"""))
        assertEquals("archive", rpcnode.toolkit.chains.arc.infrastructure.ArcNodeMode.fromJson("""{"node":"archive"}"""))
        assertEquals("full", rpcnode.toolkit.chains.arc.infrastructure.ArcNodeMode.fromJson("""{"snapshot":"full","node":"archive"}"""))
        assertEquals("full", rpcnode.toolkit.chains.arc.infrastructure.ArcNodeMode.fromJson(null))
    }
}
