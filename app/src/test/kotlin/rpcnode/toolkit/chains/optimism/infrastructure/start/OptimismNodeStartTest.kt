package rpcnode.toolkit.chains.optimism.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class OptimismNodeStartTest
{
    @Test
    fun plan_encodes_env_mode_l1_and_all_three_disk_roles()
    {
        val plan = OptimismNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.OPTIMISM,
                env = "sepolia",
                program = "op-reth",
                configFile = null,
                nodeDir = "/data/op/sepolia",
                installOptionsJson = """{"node":"archive","l1_rpc":"http://l1:8545","l1_beacon":"http://l1:5052"}""",
                diskLayoutJson = """
                  {"roles":[
                      {"id":"execution","dir":"/data/op/exec"},
                      {"id":"consensus","dir":"/data/op/cons"},
                      {"id":"snapshots","dir":"/data/op/snaps"}
                  ]}
                """.trimIndent(),
            ),
        )
        assertEquals(NetworkId.OPTIMISM, OptimismNodeStart().networkId)
        assertEquals("bin/rpcnode-optimism-run.sh", plan.launch.entry)
        assertEquals("eth_rpc", plan.height.kind)
        assertEquals("logs/optimism.log", plan.launch.logFile)
        assertTrue(plan.launch.args.contains("--toolkit-env=sepolia"))
        assertTrue(plan.launch.args.contains("--toolkit-mode=archive"))
        assertTrue(plan.launch.args.contains("--toolkit-l1-rpc=http://l1:8545"))
        assertTrue(plan.launch.args.contains("--toolkit-execution=/data/op/exec"))
        assertTrue(plan.launch.args.contains("--toolkit-consensus=/data/op/cons"))
        assertTrue(plan.launch.args.contains("--toolkit-snapshots=/data/op/snaps"))
    }

    @Test
    fun plan_defaults_to_a_full_mainnet_node_with_the_public_l1()
    {
        val plan = OptimismNodeStart().plan(
            ChainNodeStartContext(network = NetworkId.OPTIMISM, env = "x", program = "op-reth", configFile = null, nodeDir = "/n"),
        )
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-mode=full"))
        assertTrue(plan.launch.args.contains("--toolkit-l1-rpc=https://ethereum-rpc.publicnode.com"))
        assertTrue(plan.launch.args.contains("--toolkit-execution=/n"))
    }
}
