package rpcnode.toolkit.chains.hyperliquid.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class HyperliquidNodeStartTest
{
    @Test
    fun plan_encodes_env_and_chain_dir()
    {
        val plan = HyperliquidNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.HYPERLIQUID,
                env = "mainnet",
                program = "hl-visor",
                configFile = null,
                nodeDir = "/data/rpcnode/hyperliquid/mainnet/chain",
                diskLayoutJson = """
                  {"roles":[
                      {"id":"chain","dir":"/data/rpcnode/hyperliquid/mainnet/chain"}
                  ]}
                """.trimIndent(),
            ),
        )
        assertEquals("binary", plan.launch.kind)
        assertEquals("bin/hl-visor", plan.launch.entry)
        assertEquals("eth_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-chain-id=999"))
        assertTrue(plan.launch.args.contains("--toolkit-chain=/data/rpcnode/hyperliquid/mainnet/chain"))
    }

    @Test
    fun plan_defaults_testnet_chain_id()
    {
        val plan = HyperliquidNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.HYPERLIQUID,
                env = "testnet",
                program = "hl-visor",
                configFile = null,
            ),
        )
        assertTrue(plan.launch.args.contains("--toolkit-env=testnet"))
        assertTrue(plan.launch.args.contains("--toolkit-chain-id=998"))
    }
}
