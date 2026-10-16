package rpcnode.toolkit.chains.xai.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class XaiNodeStartTest
{
    @Test
    fun plan_encodes_the_parent_rpc_and_the_execution_role_and_never_a_beacon()
    {
        val plan = XaiNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.XAI,
                env = "mainnet",
                program = "nitro",
                configFile = null,
                nodeDir = "/data/xai/mainnet",
                installOptionsJson = """{"l1_rpc":"http://127.0.0.1:8647","l1_beacon":"http://ignored"}""",
                diskLayoutJson = """{"roles":[{"id":"execution","dir":"/data/xai/mainnet/exec"}]}""",
            ),
        )
        assertEquals(NetworkId.XAI, XaiNodeStart().networkId)
        assertEquals("binary", plan.launch.kind)
        assertEquals("bin/nitro", plan.launch.entry)
        assertEquals("nitro-*.tar.gz", plan.launch.extractArchiveGlob)
        assertEquals("eth_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-l1-rpc=http://127.0.0.1:8647"))
        assertTrue(plan.launch.args.contains("--toolkit-execution=/data/xai/mainnet/exec"))
        assertFalse(plan.launch.args.any { it.contains("beacon") || it.contains("ignored") })
    }

    @Test
    fun plan_defaults_to_the_public_arbitrum_one_rpc_and_the_node_dir()
    {
        val plan = XaiNodeStart().plan(
            ChainNodeStartContext(network = NetworkId.XAI, env = "mainnet", program = "nitro", configFile = null, nodeDir = "/n"),
        )
        assertTrue(plan.launch.args.contains("--toolkit-l1-rpc=https://arb1.arbitrum.io/rpc"))
        assertTrue(plan.launch.args.contains("--toolkit-execution=/n"))
    }
}
