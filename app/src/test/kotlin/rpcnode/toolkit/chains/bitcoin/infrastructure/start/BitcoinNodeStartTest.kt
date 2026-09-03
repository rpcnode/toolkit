package rpcnode.toolkit.chains.bitcoin.infrastructure.start

import kotlin.test.Test
import kotlin.test.assertEquals
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class BitcoinNodeStartTest
{
    @Test
    fun plan_binary_extract_and_cli_height()
    {
        val plan = BitcoinNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.BITCOIN,
                env = "mainnet",
                program = "bitcoin",
                configFile = "bitcoin.conf",
                nodeDir = "/data/bitcoin/mainnet/blockchain",
            ),
        )
        assertEquals("binary", plan.launch.kind)
        assertEquals("bitcoin/bin/bitcoind", plan.launch.entry)
        assertEquals(
            listOf(
                "-datadir=/data/bitcoin/mainnet/blockchain",
                "-conf=/data/bitcoin/mainnet/blockchain/bitcoin.conf",
                "-daemon=0",
            ),
            plan.launch.args,
        )
        assertEquals("*.tar.gz", plan.launch.extractArchiveGlob)
        assertEquals("bitcoin", plan.launch.normalizeDir)
        assertEquals("bitcoin_cli", plan.height.kind)
        assertEquals("rpc", plan.height.portRole)
    }

    @Test
    fun plan_testnet4_includes_chain_flag()
    {
        val plan = BitcoinNodeStart().plan(
            ChainNodeStartContext(
                network = NetworkId.BITCOIN,
                env = "testnet4",
                program = "bitcoin",
                configFile = "bitcoin.conf",
                nodeDir = "/data/bitcoin/testnet4/blockchain",
            ),
        )
        assertEquals(
            listOf(
                "-datadir=/data/bitcoin/testnet4/blockchain",
                "-conf=/data/bitcoin/testnet4/blockchain/bitcoin.conf",
                "-chain=testnet4",
                "-daemon=0",
            ),
            plan.launch.args,
        )
    }
}
