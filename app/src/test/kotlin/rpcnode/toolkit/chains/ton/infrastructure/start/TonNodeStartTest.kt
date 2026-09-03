package rpcnode.toolkit.chains.ton.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class TonNodeStartTest
{
    @Test
    fun plan_encodes_env_disks_and_history()
    {
        val plan = TonNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.TON,
                env = "mainnet",
                program = "validator-engine",
                configFile = null,
                nodeDir = "/data/rpcnode/ton/mainnet/db",
                installOptionsJson = """{"history":"archive"}""",
                diskLayoutJson = """
                  {"roles":[
                      {"id":"blockchain","dir":"/data/rpcnode/ton/mainnet/db"},
                      {"id":"archive","dir":"/data/rpcnode/ton/mainnet/archive"}
                  ]}
                """.trimIndent(),
            ),
        )
        assertEquals(NetworkId.TON, TonNodeStart().networkId)
        assertEquals("binary", plan.launch.kind)
        assertEquals("bin/rpcnode-ton-node-start.sh", plan.launch.entry)
        assertEquals("ton_http", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertEquals("logs/ton.log", plan.launch.logFile)
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-history=archive"))
        assertTrue(plan.launch.args.contains("--toolkit-blockchain=/data/rpcnode/ton/mainnet/db"))
        assertTrue(plan.launch.args.contains("--toolkit-archive=/data/rpcnode/ton/mainnet/archive"))
    }

    @Test
    fun plan_defaults_dump_history_and_mainnet()
    {
        val plan = TonNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.TON,
                env = "TESTNET",
                program = "validator-engine",
                configFile = null,
            ),
        )
        assertTrue(plan.launch.args.contains("--toolkit-env=testnet"))
        assertTrue(plan.launch.args.contains("--toolkit-history=dump"))
    }
}
