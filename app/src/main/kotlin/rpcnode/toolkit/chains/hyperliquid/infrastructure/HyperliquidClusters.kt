package rpcnode.toolkit.chains.hyperliquid.infrastructure

/**
 * Static Hyperliquid non-validator facts for one env (CDN binary + gossip seeds).
 * Live peer IPs are resolved at Start via [HyperliquidGossipPeers].
 */
data class HyperliquidCluster(
    val env: String,
    val chainId: String,
    val chainName: String,
    val watchSlug: String,
    val binaryUrl: String,
    val seedPeers: List<String>,
)

object HyperliquidClusters
{
    const val PUB_KEY_URL =
        "https://raw.githubusercontent.com/hyperliquid-dex/node/main/pub_key.asc"

    private val MAINNET = HyperliquidCluster(
        env = "mainnet",
        chainId = "999",
        chainName = "Mainnet",
        watchSlug = "hyperliquid",
        binaryUrl = "https://binaries.hyperliquid.xyz/Mainnet/hl-visor",
        seedPeers = listOf(
            "72.46.86.185",
            "72.46.86.159",
        ),
    )

    private val TESTNET = HyperliquidCluster(
        env = "testnet",
        chainId = "998",
        chainName = "Testnet",
        watchSlug = "hyperliquid-testnet",
        binaryUrl = "https://binaries.hyperliquid-testnet.xyz/Testnet/hl-visor",
        seedPeers = listOf(
            "23.81.40.132",
            "199.254.199.190",
            "199.254.199.243",
            "202.182.101.169",
            "45.12.134.122",
            "45.250.255.44",
            "72.46.86.237",
            "72.46.86.39",
        ),
    )

    fun normalizeEnv(raw: String): String
    {
        val e = raw.trim().lowercase()
        return if (e == "testnet") "testnet" else "mainnet"
    }

    fun lookup(raw: String): HyperliquidCluster =
        if (normalizeEnv(raw) == "testnet") TESTNET else MAINNET
}
