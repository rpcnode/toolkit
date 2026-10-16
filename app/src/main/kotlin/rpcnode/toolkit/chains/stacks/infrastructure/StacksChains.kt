package rpcnode.toolkit.chains.stacks.infrastructure

/** One env of Stacks: the `[burnchain] mode` (also the top directory of the data), its catalog ports and its snapshot. */
data class StacksEnv(
    val id: String,
    /** `burnchain.mode`: `mainnet`, or `krypton` for the Hiro-hosted regtest testnet. The node keeps its data under `<working_dir>/<mode>/`. */
    val mode: String,
    val basePort: Int,
    /** The tag's `sample/conf/<file>` this env starts from. */
    val sampleConf: String,
    val snapshotUrl: String,
    val publicTipUrls: List<String>,
)
{
    val rpcPort: Int get() = basePort
    val p2pPort: Int get() = basePort + 2
    val metricsPort: Int get() = basePort + 4
    val local: Boolean get() = id == "mainnet"
}

object StacksChains
{
    val MAINNET = StacksEnv(
        id = "mainnet",
        mode = "mainnet",
        basePort = 42500,
        sampleConf = "mainnet-follower-conf.toml",
        snapshotUrl = "https://archive.hiro.so/mainnet/stacks-blockchain/mainnet-stacks-blockchain-latest.tar.zst",
        publicTipUrls = listOf("https://api.hiro.so/v2/info", "https://api.mainnet.hiro.so/v2/info"),
    )
    val TESTNET = StacksEnv(
        id = "testnet",
        mode = "krypton",
        basePort = 42510,
        sampleConf = "testnet-follower-conf.toml",
        snapshotUrl = "https://archive.hiro.so/testnet/stacks-blockchain/testnet-stacks-blockchain-latest.tar.zst",
        publicTipUrls = listOf("https://api.testnet.hiro.so/v2/info"),
    )
    val ALL = listOf(MAINNET, TESTNET)

    fun env(raw: String): StacksEnv = ALL.firstOrNull { it.id == raw.trim().lowercase() } ?: MAINNET
}

/**
 * Where the mainnet follower finds Bitcoin (stacks-node needs the burnchain's P2P *and* RPC; it cannot read bitcoind's `.cookie`,
 * so by default it talks to the login-free RPC proxy of the toolkit's Bitcoin node and sends no credentials — they only matter for
 * a miner, VERIFIED in the `BurnchainConfig` docs of stacks-core 4.0.5).
 */
data class StacksBurnchain(
    val host: String,
    val rpcPort: Int,
    val p2pPort: Int,
    val username: String,
    val password: String,
)
{
    companion object
    {
        const val DEFAULT_HOST = "127.0.0.1"
        /** The Bitcoin node's login-free RPC proxy (clients.yml `rpc_public`): a follower needs no credentials, bitcoind keeps its cookie. */
        const val DEFAULT_RPC_PORT = 8432
        const val DEFAULT_P2P_PORT = 8333
    }
}

/** Values Start applies to `stacks.toml` (literal bindings of `network.yml`, kept equal by `StacksCatalogTest`). */
object StacksSettings
{
    /** Vendor default 1000 (`DEFAULT_CONNECTION_OPTIONS`, stackslib config at 4.0.5); judgement value for a public node. */
    const val MAX_HTTP_CLIENTS = 3000
    const val NOFILE = 1_048_576
    const val RELATIVE_LOG = "logs/stacks.log"
    const val CONFIG_FILE = "stacks.toml"
}
