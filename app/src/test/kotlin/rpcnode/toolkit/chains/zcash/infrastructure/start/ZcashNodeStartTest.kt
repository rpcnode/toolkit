package rpcnode.toolkit.chains.zcash.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class ZcashNodeStartTest
{
    @Test
    fun plan_extracts_zcashd_with_params_dir_note_in_proc()
    {
        val plan = ZcashNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.ZCASH,
                env = "testnet",
                program = "zcash",
                configFile = "zcash.conf",
                nodeDir = "/data/zcash/testnet/blockchain",
            ),
        )
        assertEquals("binary", plan.launch.kind)
        assertEquals("zcash/bin/zcashd", plan.launch.entry)
        assertEquals("zcash", plan.launch.normalizeDir)
        assertEquals(
            listOf(
                "-datadir=/data/zcash/testnet/blockchain",
                "-conf=/data/zcash/testnet/blockchain/zcash.conf",
                "-testnet",
                "-daemon=0",
            ),
            plan.launch.args,
        )
    }
}
