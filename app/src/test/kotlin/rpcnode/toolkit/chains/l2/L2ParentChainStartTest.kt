package rpcnode.toolkit.chains.l2

import kotlin.test.Test
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.arb.infrastructure.start.ArbNodeStart
import rpcnode.toolkit.chains.base.infrastructure.start.BaseNodeStart
import rpcnode.toolkit.chains.optimism.infrastructure.start.OptimismNodeStart
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackNetworks
import rpcnode.toolkit.chains.opstack.infrastructure.start.OpstackNodeStart
import rpcnode.toolkit.chains.robinhood.infrastructure.start.RobinhoodNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

/**
 * Every L2 must take its Ethereum parent endpoints from the saved install options on each Start: the unit is
 * re-rendered from these launch args, so changing `l1_rpc` / `l1_beacon` and restarting is all it takes.
 */
class L2ParentChainStartTest
{
    private val options = """{"l1_rpc":"http://127.0.0.1:8546","l1_beacon":"http://127.0.0.1:5053"}"""

    private fun assertUsesSavedL1(start: ChainNodeStart, network: NetworkId, env: String, program: String)
    {
        val plan = start.plan(
            ChainNodeStartContext(
                network = network,
                env = env,
                program = program,
                configFile = null,
                nodeDir = "/n",
                installOptionsJson = options,
            ),
        )
        val args = plan.launch.args
        assertTrue("--toolkit-l1-rpc=http://127.0.0.1:8546" in args, "${network.value}: $args")
        assertTrue("--toolkit-l1-beacon=http://127.0.0.1:5053" in args, "${network.value}: $args")
    }

    @Test
    fun arbitrum() = assertUsesSavedL1(ArbNodeStart(), NetworkId.ARB, "sepolia", "nitro")

    @Test
    fun robinhood() = assertUsesSavedL1(RobinhoodNodeStart(), NetworkId.ROBINHOOD, "testnet", "nitro")

    @Test
    fun base() = assertUsesSavedL1(BaseNodeStart(), NetworkId.BASE, "sepolia", "base-reth-node")

    @Test
    fun optimism() = assertUsesSavedL1(OptimismNodeStart(), NetworkId.OPTIMISM, "sepolia", "op-reth")

    @Test
    fun opstack_family()
    {
        for (spec in OpstackNetworks.ALL)
        {
            assertUsesSavedL1(OpstackNodeStart(spec), spec.network, spec.envs.last().id, spec.elFlavor.binary)
        }
    }

    @Test
    fun xai_takes_only_the_arbitrum_one_rpc_from_the_saved_options()
    {
        val plan = rpcnode.toolkit.chains.xai.infrastructure.start.XaiNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.XAI,
                env = "mainnet",
                program = "nitro",
                configFile = null,
                nodeDir = "/n",
                installOptionsJson = """{"l1_rpc":"http://127.0.0.1:8647","l1_beacon":"http://127.0.0.1:5052"}""",
            ),
        )
        assertTrue("--toolkit-l1-rpc=http://127.0.0.1:8647" in plan.launch.args, "${plan.launch.args}")
        assertTrue(plan.launch.args.none { it.startsWith("--toolkit-l1-beacon") }, "an Orbit L3 on Arbitrum One has no beacon")
    }
}
