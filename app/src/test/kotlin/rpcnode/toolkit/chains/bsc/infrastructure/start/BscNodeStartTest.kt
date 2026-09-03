package rpcnode.toolkit.chains.bsc.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class BscNodeStartTest
{
    @Test
    fun plan_encodes_datadir_and_eth_rpc_height()
    {
        val plan = BscNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.BSC,
                env = "mainnet",
                program = "geth",
                configFile = null,
                nodeDir = "/data/rpcnode/bsc/mainnet/chaindata",
                diskLayoutJson = """
                    {"roles":[
                      {"id":"chaindata","dir":"/data/rpcnode/bsc/mainnet/chaindata"},
                      {"id":"snapshots","dir":"/data/rpcnode/bsc/mainnet/snapshots"}
                    ]}
                """.trimIndent(),
                installOptionsJson = """{"snapshot":"pruned"}""",
            ),
        )
        assertEquals("geth", plan.launch.entry)
        assertEquals("eth_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertTrue(plan.launch.args.contains("--toolkit-datadir=/data/rpcnode/bsc/mainnet/chaindata"))
        assertTrue(plan.launch.args.contains("--toolkit-snapshots=/data/rpcnode/bsc/mainnet/snapshots"))
        assertTrue(plan.launch.args.contains("--toolkit-snapshot=pruned"))
    }
}
