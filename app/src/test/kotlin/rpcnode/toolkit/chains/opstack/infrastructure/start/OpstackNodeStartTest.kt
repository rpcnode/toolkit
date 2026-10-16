package rpcnode.toolkit.chains.opstack.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackNetworks
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class OpstackNodeStartTest
{
    private val diskLayout = """
        {"roles":[
            {"id":"execution","dir":"/data/op/exec"},
            {"id":"consensus","dir":"/data/op/cons"},
            {"id":"snapshots","dir":"/data/op/snaps"}
        ]}
    """.trimIndent()

    @Test
    fun plan_encodes_env_mode_snapshot_l1_and_all_three_disk_roles_for_every_network()
    {
        for (spec in OpstackNetworks.ALL)
        {
            val env = spec.envs.last().id
            val plan = OpstackNodeStart(spec).plan(
                ChainNodeStartContext(
                    network = spec.network,
                    env = env,
                    program = spec.elFlavor.binary,
                    configFile = null,
                    nodeDir = "/data/op/node",
                    installOptionsJson = """{"node":"archive","restore":"none","l1_rpc":"http://l1:8545","l1_beacon":"http://l1:5052"}""",
                    diskLayoutJson = diskLayout,
                ),
            )
            assertEquals(spec.network, OpstackNodeStart(spec).networkId)
            assertEquals("bin/rpcnode-${spec.network.value}-run.sh", plan.launch.entry)
            assertEquals("logs/${spec.network.value}.log", plan.launch.logFile)
            assertEquals("binary", plan.launch.kind)
            assertEquals("eth_rpc", plan.height.kind)
            assertEquals("http", plan.height.portRole)
            val args = plan.launch.args
            assertTrue("--toolkit-env=$env" in args, "${spec.network.value}: $args")
            assertTrue("--toolkit-mode=archive" in args)
            assertTrue("--toolkit-restore=none" in args)
            assertTrue("--toolkit-l1-rpc=http://l1:8545" in args)
            assertTrue("--toolkit-l1-beacon=http://l1:5052" in args)
            assertTrue("--toolkit-execution=/data/op/exec" in args)
            assertTrue("--toolkit-consensus=/data/op/cons" in args)
            assertTrue("--toolkit-snapshots=/data/op/snaps" in args)
            assertTrue(args.none { it.startsWith("--toolkit-snapshot-url=") }, "no URL was given")
        }
    }

    @Test
    fun the_operator_snapshot_url_is_carried_in_the_launch_args()
    {
        val plan = OpstackNodeStart(OpstackNetworks.MODE).plan(
            ChainNodeStartContext(
                network = OpstackNetworks.MODE.network, env = "mainnet", program = "op-reth", configFile = null, nodeDir = "/n",
                installOptionsJson = """{"snapshot_url":"  https://files.example/mode.tar.lz4  "}""",
            ),
        )
        assertTrue("--toolkit-snapshot-url=https://files.example/mode.tar.lz4" in plan.launch.args)
        assertTrue("--toolkit-restore=snapshot" in plan.launch.args)
    }

    @Test
    fun plan_defaults_to_a_full_mainnet_node_that_syncs_without_a_snapshot_with_the_public_l1()
    {
        val plan = OpstackNodeStart(OpstackNetworks.UNICHAIN).plan(
            ChainNodeStartContext(network = OpstackNetworks.UNICHAIN.network, env = "x", program = "op-reth", configFile = null, nodeDir = "/n"),
        )
        assertTrue("--toolkit-env=mainnet" in plan.launch.args)
        assertTrue("--toolkit-mode=full" in plan.launch.args)
        assertTrue("--toolkit-restore=none" in plan.launch.args)
        assertTrue("--toolkit-l1-rpc=https://ethereum-rpc.publicnode.com" in plan.launch.args)
        assertTrue("--toolkit-execution=/n" in plan.launch.args)
        val ink = OpstackNodeStart(OpstackNetworks.INK).plan(
            ChainNodeStartContext(network = OpstackNetworks.INK.network, env = "x", program = "op-reth", configFile = null, nodeDir = "/n"),
        )
        assertTrue("--toolkit-restore=snapshot" in ink.launch.args)
    }

    @Test
    fun a_sepolia_node_uses_the_sepolia_l1_and_unknown_envs_fall_back_to_mainnet()
    {
        val sepolia = OpstackNodeStart(OpstackNetworks.INK).plan(
            ChainNodeStartContext(network = OpstackNetworks.INK.network, env = "sepolia", program = "op-reth", configFile = null, nodeDir = "/n"),
        )
        assertTrue("--toolkit-l1-rpc=https://ethereum-sepolia-rpc.publicnode.com" in sepolia.launch.args)
        // lisk ships mainnet only: a sepolia request must not produce a Sepolia node on a Mainnet binary set
        val lisk = OpstackNodeStart(OpstackNetworks.LISK).plan(
            ChainNodeStartContext(network = OpstackNetworks.LISK.network, env = "sepolia", program = "op-reth", configFile = null, nodeDir = "/n"),
        )
        assertTrue("--toolkit-env=mainnet" in lisk.launch.args)
        assertFalse(lisk.launch.args.any { it.contains("sepolia") })
    }
}
