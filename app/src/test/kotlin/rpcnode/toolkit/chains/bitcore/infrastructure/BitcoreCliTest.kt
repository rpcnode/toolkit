package rpcnode.toolkit.chains.bitcore.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BitcoreCliTest
{
    @Test
    fun bitcoind_uses_absolute_datadir_conf_and_chain_for_testnet4()
    {
        val args = BitcoreCli.daemonArgs(
            nodeDir = "/data/bitcoin/testnet4/blockchain",
            configFile = "bitcoin.conf",
            env = "testnet4",
            chainArg = BitcoreCli::bitcoinChainArg,
        )
        assertEquals(
            listOf(
                "-datadir=/data/bitcoin/testnet4/blockchain",
                "-conf=/data/bitcoin/testnet4/blockchain/bitcoin.conf",
                "-chain=testnet4",
                "-daemon=0",
            ),
            args,
        )
    }

    @Test
    fun classic_fork_testnet_uses_chain_test()
    {
        val args = BitcoreCli.daemonArgs(
            nodeDir = "/data/ltc/testnet/blockchain",
            configFile = "litecoin.conf",
            env = "testnet",
            chainArg = BitcoreCli::classicChainArg,
        )
        assertEquals(
            listOf(
                "-datadir=/data/ltc/testnet/blockchain",
                "-conf=/data/ltc/testnet/blockchain/litecoin.conf",
                "-chain=test",
                "-daemon=0",
            ),
            args,
        )
    }

    @Test
    fun bitcoin_mainnet_omits_chain_flag()
    {
        val args = BitcoreCli.daemonArgs(
            nodeDir = "/data/bitcoin/mainnet/blockchain",
            configFile = "bitcoin.conf",
            env = "mainnet",
            chainArg = BitcoreCli::bitcoinChainArg,
        )
        assertEquals(
            listOf(
                "-datadir=/data/bitcoin/mainnet/blockchain",
                "-conf=/data/bitcoin/mainnet/blockchain/bitcoin.conf",
                "-daemon=0",
            ),
            args,
        )
    }

    @Test
    fun chain_args_map_env_ids()
    {
        assertNull(BitcoreCli.bitcoinChainArg("mainnet"))
        assertEquals("-chain=testnet4", BitcoreCli.bitcoinChainArg("testnet4"))
        assertEquals("-chain=test", BitcoreCli.classicChainArg("testnet"))
        assertEquals("-chain=regtest", BitcoreCli.classicChainArg("regtest"))
    }
}
