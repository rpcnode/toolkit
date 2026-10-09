package rpcnode.toolkit.chains.xrpl.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class XrplNodeStartTest
{
    @Test
    fun plan_encodes_env_ledger_and_history()
    {
        val plan = XrplNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.XRPL,
                env = "mainnet",
                program = "xrpld",
                configFile = null,
                nodeDir = "/data/rpcnode/xrpl/mainnet/ledger",
                diskLayoutJson = """
                  {"roles":[
                      {"id":"ledger","dir":"/data/rpcnode/xrpl/mainnet/ledger"}
                  ]}
                """.trimIndent(),
                installOptionsJson = """{"xrpl_history":"day"}""",
            ),
        )
        assertEquals(NetworkId.XRPL, XrplNodeStart().networkId)
        assertEquals("binary", plan.launch.kind)
        assertEquals("bin/xrpld", plan.launch.entry)
        assertEquals("xrpl_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertEquals("logs/xrpld.log", plan.launch.logFile)
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-history=day"))
        assertTrue(plan.launch.args.contains("--toolkit-ledger=/data/rpcnode/xrpl/mainnet/ledger"))
        assertEquals("xrpld-*.deb", plan.launch.extractArchiveGlob)
    }

    @Test
    fun plan_defaults_weeks_history_and_testnet_env()
    {
        val plan = XrplNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.XRPL,
                env = "TESTNET",
                program = "xrpld",
                configFile = null,
            ),
        )
        assertTrue(plan.launch.args.contains("--toolkit-env=testnet"))
        assertTrue(plan.launch.args.contains("--toolkit-history=weeks"))
    }
}
