package rpcnode.toolkit.chains.starknet.infrastructure

/** Static Starknet facts per env (`mainnet`, `sepolia`) for the Juno recipe. */
data class StarknetEnv(
    val id: String,
    /** Juno `--network`. */
    val network: String,
    /** WebSocket of the Ethereum chain the Starknet core contract lives on (`--eth-node`); public stop-gap default. */
    val defaultEthNode: String,
    val ports: StarknetPorts,
)

/** Catalog-fixed ports — must match `chains/starknet/clients.yml`. p2p / gRPC / pprof / the log-level update port stay off. */
data class StarknetPorts(val http: Int, val ws: Int, val metrics: Int)

object StarknetChain
{
    private val MAINNET = StarknetEnv(
        id = "mainnet",
        network = "mainnet",
        defaultEthNode = "wss://ethereum-rpc.publicnode.com",
        ports = StarknetPorts(http = 42530, ws = 42531, metrics = 42534),
    )
    private val SEPOLIA = StarknetEnv(
        id = "sepolia",
        network = "sepolia",
        defaultEthNode = "wss://ethereum-sepolia-rpc.publicnode.com",
        ports = StarknetPorts(http = 42540, ws = 42541, metrics = 42544),
    )

    val ENVS: List<StarknetEnv> = listOf(MAINNET, SEPOLIA)

    fun lookup(raw: String): StarknetEnv = if (raw.trim().lowercase() == "sepolia") SEPOLIA else MAINNET

    // Capacity knobs: network.yml `clientConfig.bindings` literals carry the same values (StarknetCatalogTest).
    /** `--db-cache-size` MB; the default is 1024, Juno's docs suggest 2048-4096 on a RAM-rich RPC host. */
    const val DB_CACHE_SIZE_MB = "4096"

    /** `--rpc-max-block-scan` (default unlimited): bounds one `starknet_getEvents` scan. */
    const val RPC_MAX_BLOCK_SCAN = "10000"

    /** `--rpc-request-timeout` (default 1m). */
    const val RPC_REQUEST_TIMEOUT = "30s"

    /** `--rpc-max-concurrent-requests` / `--rpc-max-request-queue` (both default 256000). */
    const val RPC_MAX_CONCURRENT_REQUESTS = "4096"
    const val RPC_MAX_REQUEST_QUEUE = "4096"
    const val LIMIT_NOFILE = "1048576"

    /**
     * Retention window of a pruned snapshot when the operator sets none. Juno's own bare `--prune-mode` keeps 128 blocks, far too
     * little for an RPC provider (a lagging client or an explorer asks for older blocks and state); 100000 blocks is about a week
     * at ~6 s per block. The extra disk was not measured — the pruned snapshot is ~102 GB at the 128-block window.
     */
    const val DEFAULT_PRUNE_WINDOW = "100000"

    const val SNAPSHOT_PRUNED = "pruned"
    const val SNAPSHOT_FULL = "full"

    fun normalizeSnapshot(raw: String?): String =
        if (raw?.trim()?.lowercase() == SNAPSHOT_FULL) SNAPSHOT_FULL else SNAPSHOT_PRUNED
}
