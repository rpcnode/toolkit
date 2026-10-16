package rpcnode.toolkit.chains.stellar.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class StellarNodeStartTest
{
    private fun ctx(env: String, options: String = "", layout: String = "") = ChainNodeStartContext(
        network = NetworkId.STELLAR,
        env = env,
        program = "stellar-rpc",
        configFile = null,
        nodeDir = "/data/rpcnode/stellar/$env",
        installOptionsJson = options,
        diskLayoutJson = layout,
    )

    @Test
    fun plan_encodes_env_history_and_the_blockchain_role()
    {
        val plan = StellarNodeStart().plan(
            ctx("mainnet", """{"history":"month"}""", """{"roles":[{"id":"blockchain","dir":"/data/rpcnode/stellar/mainnet/db"}]}"""),
        )
        assertEquals("binary", plan.launch.kind)
        assertEquals("bin/stellar-rpc", plan.launch.entry)
        assertEquals("logs/stellar-rpc.log", plan.launch.logFile)
        assertNull(plan.launch.extractArchiveGlob)
        assertTrue("--toolkit-env=mainnet" in plan.launch.args)
        assertTrue("--toolkit-history=month" in plan.launch.args)
        assertTrue("--toolkit-blockchain=/data/rpcnode/stellar/mainnet/db" in plan.launch.args)
        assertEquals("stellar_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
    }

    @Test
    fun defaults_are_a_one_week_window()
    {
        val plan = StellarNodeStart().plan(ctx("testnet"))
        assertTrue("--toolkit-env=testnet" in plan.launch.args)
        assertTrue("--toolkit-history=week" in plan.launch.args)
    }
}
