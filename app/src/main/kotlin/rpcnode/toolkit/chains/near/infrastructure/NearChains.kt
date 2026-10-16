package rpcnode.toolkit.chains.near.infrastructure

/** One env of NEAR: the `neard init --chain-id`, its catalog port block and the public RPCs (tip + boot-node discovery). */
data class NearEnv(
    val id: String,
    val chainId: String,
    val basePort: Int,
    /** Same list as `publicTip.urls` in network.yml (`NearCatalogTest` keeps them equal). */
    val publicRpcUrls: List<String>,
)
{
    val rpcPort: Int get() = basePort
    val p2pPort: Int get() = basePort + 2
    val metricsPort: Int get() = basePort + 4
}

object NearChains
{
    val MAINNET = NearEnv(
        id = "mainnet",
        chainId = "mainnet",
        basePort = 42080,
        publicRpcUrls = listOf(
            "https://rpc.mainnet.near.org",
            "https://rpc.mainnet.fastnear.com",
            "https://near.drpc.org",
        ),
    )
    val TESTNET = NearEnv(
        id = "testnet",
        chainId = "testnet",
        basePort = 42090,
        publicRpcUrls = listOf(
            "https://rpc.testnet.near.org",
            "https://rpc.testnet.fastnear.com",
            "https://near-testnet.drpc.org",
        ),
    )
    val ALL = listOf(MAINNET, TESTNET)

    fun env(raw: String): NearEnv = ALL.firstOrNull { it.id == raw.trim().lowercase() } ?: MAINNET

    /** Where `neard init --download-config rpc` takes its files (the official build bucket, `near-nodes.io`). */
    const val DEPLOY_BUCKET = "https://s3-us-west-1.amazonaws.com/build.nearprotocol.com/nearcore-deploy"
}

/**
 * Values the Start applies to `config.json` on every run (`network.yml` literal bindings, kept equal by `NearCatalogTest`).
 * Every other key of `config.json` is the operator's.
 */
object NearSettings
{
    /** The `rpc` profile of testnet ships `true`; the debug RPC must never be on a public node. */
    const val ENABLE_DEBUG_RPC = false

    /** Vendor default 4 (nearcore `view_client_threads`); judgement value for a read-heavy public node on 8+ cores. */
    const val VIEW_CLIENT_THREADS = 16

    const val NOFILE = 1_048_576
    const val RELATIVE_LOG = "logs/near.log"
}
