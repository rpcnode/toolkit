package rpcnode.toolkit.chains.aptos.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class AptosNodeStartTest
{
    private fun ctx(env: String, options: String = "", layout: String = "", dir: String = "/data/node") = ChainNodeStartContext(
        network = NetworkId.APTOS, env = env, program = "aptos-node", configFile = null,
        nodeDir = dir, installOptionsJson = options, diskLayoutJson = layout,
    )

    @Test
    fun plan_encodes_env_bootstrap_and_the_state_role()
    {
        val plan = AptosNodeStart().plan(
            ctx("testnet", """{"bootstrap":"genesis"}""", """{"roles":[{"id":"state","dir":"/data/rpcnode/aptos/testnet/state"}]}"""),
        )
        assertEquals(NetworkId.APTOS, AptosNodeStart().networkId)
        assertEquals("binary", plan.launch.kind)
        assertEquals("bin/aptos-node", plan.launch.entry)
        assertEquals("logs/aptos.log", plan.launch.logFile)
        assertEquals("aptos-node-performance-ubuntu-*.tgz", plan.launch.extractArchiveGlob)
        assertTrue("--toolkit-env=testnet" in plan.launch.args)
        assertTrue("--toolkit-bootstrap=genesis" in plan.launch.args)
        assertTrue("--toolkit-state=/data/rpcnode/aptos/testnet/state" in plan.launch.args)
        assertEquals("aptos_rest", plan.height.kind)
        assertEquals("http", plan.height.portRole)
    }

    @Test
    fun defaults_are_fast_mainnet_in_the_node_dir()
    {
        val plan = AptosNodeStart().plan(ctx("MAINNET"))
        assertTrue("--toolkit-env=mainnet" in plan.launch.args)
        assertTrue("--toolkit-bootstrap=fast" in plan.launch.args)
        assertTrue("--toolkit-state=/data/node" in plan.launch.args)
        assertTrue("--toolkit-bootstrap=fast" in AptosNodeStart().plan(ctx("mainnet", """{"bootstrap":"x"}""")).launch.args)
    }
}
