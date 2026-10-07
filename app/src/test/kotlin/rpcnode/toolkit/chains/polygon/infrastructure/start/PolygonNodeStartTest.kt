package rpcnode.toolkit.chains.polygon.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class PolygonNodeStartTest
{
    @Test
    fun plan_full_mode_default()
    {
        val plan = PolygonNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.POLYGON,
                env = "mainnet",
                program = "bor",
                configFile = null,
                nodeDir = "/data/rpcnode/polygon/mainnet/bor",
            ),
        )
        assertEquals("binary", plan.launch.kind)
        assertEquals("bor", plan.launch.entry)
        assertEquals("eth_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertTrue(plan.launch.args.contains("--toolkit-archive=0"))
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-bor=/data/rpcnode/polygon/mainnet/bor/datadir"))
        assertTrue(plan.launch.args.any { it.startsWith("--toolkit-eth-rpc=") })
    }

    @Test
    fun plan_archive_from_install_options()
    {
        val plan = PolygonNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.POLYGON,
                env = "amoy",
                program = "bor",
                configFile = null,
                installOptionsJson = """{"node":"archive"}""",
            ),
        )
        assertTrue(plan.launch.args.contains("--toolkit-archive=1"))
        assertTrue(plan.launch.args.contains("--toolkit-env=amoy"))
        assertTrue(plan.launch.args.contains("--toolkit-chain-id=80002"))
    }
}
