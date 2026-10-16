package rpcnode.toolkit.chains.robinhood.infrastructure

/** Static Robinhood Chain facts for one env (`testnet` = Robinhood Chain Sepolia). */
data class RobinhoodCluster(
    val env: String,
    /** File name (in the node dir) of the chain-info JSON that the clients sync downloads. */
    val chainInfoFile: String,
    /** Sequencer feeds for `--node.feed.input.url`. */
    val feeds: List<String>,
    /**
     * `--execution.forwarding-target`: where a non-sequencer node sends submitted transactions. Nitro refuses to start without
     * it ("ForwardingTarget not set and not sequencer"); the chain-info file does not carry it. The chain's public RPC forwards.
     */
    val forwardingTarget: String,
    /** Archive snapshots of this env use the PathDB scheme (mainnet) or the hash scheme (testnet). */
    val archivePathScheme: Boolean,
)

object RobinhoodClusters
{
    const val SNAPSHOT_BASE = "https://robinhood-snapshots.offchainlabs.com/"

    private val MAINNET = RobinhoodCluster(
        env = "mainnet",
        chainInfoFile = "robinhood-chain-info.json",
        // the primary feed and the delayed backup run side by side; Nitro de-duplicates by sequence number
        feeds = listOf("wss://feed.mainnet.chain.robinhood.com", "wss://delayed-feed.mainnet.chain.robinhood.com"),
        forwardingTarget = "https://rpc.mainnet.chain.robinhood.com",
        archivePathScheme = true,
    )
    private val TESTNET = RobinhoodCluster(
        env = "testnet",
        chainInfoFile = "robinhood-chain-testnet-info.json",
        feeds = listOf("wss://feed.testnet.chain.robinhood.com"),
        forwardingTarget = "https://rpc.testnet.chain.robinhood.com",
        archivePathScheme = false,
    )

    fun normalizeEnv(raw: String): String = if (raw.trim().lowercase() == "testnet") "testnet" else "mainnet"

    fun lookup(raw: String): RobinhoodCluster = if (normalizeEnv(raw) == "testnet") TESTNET else MAINNET

    fun normalizeSnapshot(raw: String?): String = if (raw?.trim()?.lowercase() == "archive") "archive" else "pruned"
}

/** Catalog-fixed ports per env — must match `chains/robinhood/clients.yml`. */
data class RobinhoodPorts(val http: Int, val ws: Int)

object RobinhoodPortTable
{
    fun forEnv(env: String): RobinhoodPorts =
        if (RobinhoodClusters.normalizeEnv(env) == "testnet") RobinhoodPorts(http = 8677, ws = 8678)
        else RobinhoodPorts(http = 8667, ws = 8668)
}
