package rpcnode.toolkit.chains.ethereum.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class EthereumNodeStartTest
{
    @Test
    fun plan_full_mode_default()
    {
        val plan = EthereumNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.ETHEREUM,
                env = "mainnet",
                program = "geth",
                configFile = null,
                nodeDir = "/data/rpcnode/ethereum/mainnet/geth",
            ),
        )
        assertEquals("binary", plan.launch.kind)
        assertEquals("geth", plan.launch.entry)
        assertEquals("eth_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertTrue(plan.launch.args.contains("--toolkit-archive=0"))
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-execution=/data/rpcnode/ethereum/mainnet/geth/datadir"))
    }

    @Test
    fun plan_archive_from_install_options()
    {
        val plan = EthereumNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.ETHEREUM,
                env = "sepolia",
                program = "geth",
                configFile = null,
                installOptionsJson = """{"node":"archive"}""",
            ),
        )
        assertTrue(plan.launch.args.contains("--toolkit-archive=1"))
        assertTrue(plan.launch.args.contains("--toolkit-env=sepolia"))
    }
}
