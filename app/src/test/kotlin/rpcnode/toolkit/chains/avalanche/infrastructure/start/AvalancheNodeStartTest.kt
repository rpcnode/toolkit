package rpcnode.toolkit.chains.avalanche.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class AvalancheNodeStartTest
{
    @Test
    fun plan_encodes_env_mode_and_the_blockchain_role()
    {
        val plan = AvalancheNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.AVALANCHE,
                env = "fuji",
                program = "avalanchego",
                configFile = null,
                nodeDir = "/data/rpcnode/avalanche/fuji/node",
                installOptionsJson = """{"node":"archive"}""",
                diskLayoutJson = """{"roles":[{"id":"blockchain","dir":"/data/rpcnode/avalanche/fuji/chain"}]}""",
            ),
        )
        assertEquals(NetworkId.AVALANCHE, AvalancheNodeStart().networkId)
        assertEquals("binary", plan.launch.kind)
        assertEquals("bin/avalanchego", plan.launch.entry)
        assertEquals("eth_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertEquals("logs/avalanche.log", plan.launch.logFile)
        assertEquals("avalanchego-linux-*.tar.gz", plan.launch.extractArchiveGlob)
        assertTrue(plan.launch.args.contains("--toolkit-env=fuji"))
        assertTrue(plan.launch.args.contains("--toolkit-mode=archive"))
        assertTrue(plan.launch.args.contains("--toolkit-blockchain=/data/rpcnode/avalanche/fuji/chain"))
    }

    @Test
    fun plan_defaults_to_a_full_mainnet_node()
    {
        val plan = AvalancheNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.AVALANCHE,
                env = "MAINNET",
                program = "avalanchego",
                configFile = null,
                nodeDir = "/data/node",
            ),
        )
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-mode=full"))
        assertTrue(plan.launch.args.contains("--toolkit-blockchain=/data/node"), "falls back to the node dir")
    }
}
