package rpcnode.toolkit.chains.robinhood.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class RobinhoodNodeStartTest
{
    @Test
    fun plan_encodes_env_snapshot_l1_and_the_execution_role()
    {
        val plan = RobinhoodNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.ROBINHOOD,
                env = "testnet",
                program = "nitro",
                configFile = null,
                nodeDir = "/data/rh/testnet",
                installOptionsJson = """{"snapshot":"archive","l1_rpc":"http://l1.local:8545","l1_beacon":"http://l1.local:5052"}""",
                diskLayoutJson = """{"roles":[{"id":"execution","dir":"/data/rh/testnet/exec"}]}""",
            ),
        )
        assertEquals(NetworkId.ROBINHOOD, RobinhoodNodeStart().networkId)
        assertEquals("nitro", plan.launch.entry)
        assertEquals("nitro-*.tar.gz", plan.launch.extractArchiveGlob)
        assertEquals("eth_rpc", plan.height.kind)
        assertTrue(plan.launch.args.contains("--toolkit-env=testnet"))
        assertTrue(plan.launch.args.contains("--toolkit-snapshot=archive"))
        assertTrue(plan.launch.args.contains("--toolkit-l1-rpc=http://l1.local:8545"))
        assertTrue(plan.launch.args.contains("--toolkit-l1-beacon=http://l1.local:5052"))
        assertTrue(plan.launch.args.contains("--toolkit-execution=/data/rh/testnet/exec"))
    }

    @Test
    fun plan_defaults_to_pruned_with_public_l1_for_the_env()
    {
        val plan = RobinhoodNodeStart().plan(
            ChainNodeStartContext(network = NetworkId.ROBINHOOD, env = "testnet", program = "nitro", configFile = null, nodeDir = "/n"),
        )
        assertTrue(plan.launch.args.contains("--toolkit-snapshot=pruned"))
        assertTrue(plan.launch.args.contains("--toolkit-l1-rpc=https://ethereum-sepolia-rpc.publicnode.com"), "testnet posts to Sepolia")
        val main = RobinhoodNodeStart().plan(
            ChainNodeStartContext(network = NetworkId.ROBINHOOD, env = "mainnet", program = "nitro", configFile = null, nodeDir = "/n"),
        )
        assertTrue(main.launch.args.contains("--toolkit-l1-rpc=https://ethereum-rpc.publicnode.com"))
    }
}
