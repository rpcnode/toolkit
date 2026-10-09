package rpcnode.toolkit.chains.zcash.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZcashCliTest
{
    @Test
    fun daemon_uses_absolute_paths_and_testnet_flag()
    {
        val args = ZcashCli.daemonArgs(
            nodeDir = "/data/zcash/testnet/blockchain",
            configFile = "zcash.conf",
            env = "testnet",
        )
        assertEquals(
            listOf(
                "-datadir=/data/zcash/testnet/blockchain",
                "-conf=/data/zcash/testnet/blockchain/zcash.conf",
                "-testnet",
                "-daemon=0",
            ),
            args,
        )
    }

    @Test
    fun mainnet_omits_network_flag()
    {
        val args = ZcashCli.daemonArgs(
            nodeDir = "/data/zcash/mainnet/blockchain",
            configFile = "zcash.conf",
            env = "mainnet",
        )
        assertEquals(
            listOf(
                "-datadir=/data/zcash/mainnet/blockchain",
                "-conf=/data/zcash/mainnet/blockchain/zcash.conf",
                "-daemon=0",
            ),
            args,
        )
    }

    @Test
    fun regtest_uses_regtest_flag()
    {
        assertEquals("-regtest", ZcashCli.networkFlag("regtest"))
        assertNull(ZcashCli.networkFlag("mainnet"))
    }
}
