package rpcnode.toolkit.chains.bitcore.infrastructure

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId

/** Per-network facts for a bitcoind-style daemon (Core and popular forks). */
data class BitcoreChainSpec(
    val networkId: NetworkId,
    val programId: String,
    val normalizeDir: String,
    val daemonEntry: String,
    val cliEntry: String,
    val configFile: String,
    val githubRepo: String,
    val chainArg: (String) -> String?,
    val supportedEnvs: Set<EnvId>,
)

object BitcoreChainSpecs
{
    val BITCOIN = BitcoreChainSpec(
        networkId = NetworkId.BITCOIN,
        programId = "bitcoin",
        normalizeDir = "bitcoin",
        daemonEntry = "bitcoin/bin/bitcoind",
        cliEntry = "bitcoin/bin/bitcoin-cli",
        configFile = "bitcoin.conf",
        githubRepo = "bitcoin/bitcoin",
        chainArg = BitcoreCli::bitcoinChainArg,
        supportedEnvs = setOf(EnvId.MAINNET, EnvId.TESTNET4, EnvId.SIGNET, EnvId.REGTEST),
    )

    val LTC = BitcoreChainSpec(
        networkId = NetworkId.LTC,
        programId = "litecoin",
        normalizeDir = "litecoin",
        daemonEntry = "litecoin/bin/litecoind",
        cliEntry = "litecoin/bin/litecoin-cli",
        configFile = "litecoin.conf",
        githubRepo = "litecoin-project/litecoin",
        chainArg = BitcoreCli::classicChainArg,
        supportedEnvs = setOf(EnvId.MAINNET, EnvId.TESTNET, EnvId.REGTEST),
    )

    val DOGE = BitcoreChainSpec(
        networkId = NetworkId.DOGE,
        programId = "dogecoin",
        normalizeDir = "dogecoin",
        daemonEntry = "dogecoin/bin/dogecoind",
        cliEntry = "dogecoin/bin/dogecoin-cli",
        configFile = "dogecoin.conf",
        githubRepo = "dogecoin/dogecoin",
        chainArg = BitcoreCli::classicChainArg,
        supportedEnvs = setOf(EnvId.MAINNET, EnvId.TESTNET, EnvId.REGTEST),
    )

    val DASH = BitcoreChainSpec(
        networkId = NetworkId.DASH,
        programId = "dash",
        normalizeDir = "dashcore",
        daemonEntry = "dashcore/bin/dashd",
        cliEntry = "dashcore/bin/dash-cli",
        configFile = "dash.conf",
        githubRepo = "dashpay/dash",
        chainArg = BitcoreCli::classicChainArg,
        supportedEnvs = setOf(EnvId.MAINNET, EnvId.TESTNET, EnvId.REGTEST),
    )

    val BCH = BitcoreChainSpec(
        networkId = NetworkId.BCH,
        programId = "bitcoin-cash",
        normalizeDir = "bitcoin-cash-node",
        daemonEntry = "bitcoin-cash-node/bin/bitcoind",
        cliEntry = "bitcoin-cash-node/bin/bitcoin-cli",
        configFile = "bitcoin.conf",
        githubRepo = "bitcoin-cash-node/bitcoin-cash-node",
        chainArg = BitcoreCli::classicChainArg,
        supportedEnvs = setOf(EnvId.MAINNET, EnvId.TESTNET, EnvId.REGTEST),
    )

    val ALL = listOf(BITCOIN, LTC, DOGE, DASH, BCH)

    fun byProgramId(programId: String): BitcoreChainSpec? =
        ALL.firstOrNull { it.programId.equals(programId.trim(), ignoreCase = true) }

    fun byNetwork(id: NetworkId): BitcoreChainSpec? =
        ALL.firstOrNull { it.networkId == id }
}
