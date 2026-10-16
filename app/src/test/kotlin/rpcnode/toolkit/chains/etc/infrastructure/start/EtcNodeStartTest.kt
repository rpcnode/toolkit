package rpcnode.toolkit.chains.etc.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class EtcNodeStartTest
{
    private fun ctx(env: String, options: String = "", layout: String = "") = ChainNodeStartContext(
        network = NetworkId.ETC,
        env = env,
        program = "core-geth",
        configFile = null,
        nodeDir = "/data/rpcnode/etc/$env",
        installOptionsJson = options,
        diskLayoutJson = layout,
    )

    @Test
    fun default_plan_is_a_full_mainnet_binary_launch()
    {
        val plan = EtcNodeStart().plan(ctx("mainnet"))
        assertEquals(NetworkId.ETC, EtcNodeStart().networkId)
        assertEquals("binary", plan.launch.kind)
        assertEquals("bin/geth", plan.launch.entry)
        assertTrue("--toolkit-env=mainnet" in plan.launch.args)
        assertTrue("--toolkit-archive=0" in plan.launch.args)
        assertTrue("--toolkit-chain-id=61" in plan.launch.args)
        assertTrue("--toolkit-datadir=/data/rpcnode/etc/mainnet/datadir" in plan.launch.args)
        assertEquals("eth_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
    }

    @Test
    fun archive_mordor_and_the_chaindata_role_win()
    {
        val plan = EtcNodeStart().plan(
            ctx("mordor", """{"node":"archive"}""", """{"roles":[{"id":"chaindata","dir":"/mnt/nvme/etc"}]}"""),
        )
        assertTrue("--toolkit-archive=1" in plan.launch.args)
        assertTrue("--toolkit-chain-id=63" in plan.launch.args)
        assertTrue("--toolkit-datadir=/mnt/nvme/etc/datadir" in plan.launch.args)
    }
}
