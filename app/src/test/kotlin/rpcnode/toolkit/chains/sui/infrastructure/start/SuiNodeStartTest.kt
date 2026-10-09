package rpcnode.toolkit.chains.sui.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class SuiNodeStartTest
{
    @Test
    fun plan_encodes_env_and_disk_roles()
    {
        val plan = SuiNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.SUI,
                env = "mainnet",
                program = "sui-node",
                configFile = null,
                nodeDir = "/data/rpcnode/sui/mainnet/db",
                diskLayoutJson = """
                  {"roles":[
                      {"id":"state","dir":"/data/rpcnode/sui/mainnet/db"},
                      {"id":"index","dir":"/data/rpcnode/sui/mainnet/index"}
                  ]}
                """.trimIndent(),
            ),
        )
        assertEquals(NetworkId.SUI, SuiNodeStart().networkId)
        assertEquals("binary", plan.launch.kind)
        assertEquals("bin/sui-node", plan.launch.entry)
        assertEquals("sui_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        assertEquals("logs/sui.log", plan.launch.logFile)
        assertTrue(plan.launch.args.contains("--toolkit-env=mainnet"))
        assertTrue(plan.launch.args.contains("--toolkit-state=/data/rpcnode/sui/mainnet/db"))
        assertTrue(plan.launch.args.contains("--toolkit-index=/data/rpcnode/sui/mainnet/index"))
        assertEquals("sui-*.tgz", plan.launch.extractArchiveGlob)
    }

    @Test
    fun plan_defaults_testnet_env()
    {
        val plan = SuiNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.SUI,
                env = "TESTNET",
                program = "sui-node",
                configFile = null,
            ),
        )
        assertTrue(plan.launch.args.contains("--toolkit-env=testnet"))
    }
}
