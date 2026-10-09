package rpcnode.toolkit.chains.arb.infrastructure

/** Static Arbitrum Nitro facts for one env. */
data class ArbCluster(
    val env: String,
    val chainId: String,
    val watchSlug: String,
    val initLatest: String,
    val initUrl: String,
)

object ArbClusters
{
    const val ARCHIVE_POINTER_MAINNET =
        "https://snapshot.arbitrum.foundation/arb1/latest-archive-path.txt"
    const val ARCHIVE_POINTER_SEPOLIA =
        "https://snapshot.arbitrum.foundation/sepolia-rollup/latest-archive-path.txt"
    const val SNAPSHOT_BASE = "https://snapshot.arbitrum.foundation/"

    fun lookup(env: String): ArbCluster
    {
        return when (env.trim().lowercase())
        {
            "sepolia" -> ArbCluster(
                env = "sepolia",
                chainId = "421614",
                watchSlug = "arb-sepolia",
                initLatest = "pruned",
                initUrl = "https://snapshot.arbitrum.foundation/sepolia/nitro-pruned.tar",
            )
            else -> ArbCluster(
                env = "mainnet",
                chainId = "42161",
                watchSlug = "arb",
                initLatest = "pruned",
                initUrl = "https://snapshot.arbitrum.foundation/arb1/nitro-pruned.tar",
            )
        }
    }

    /** Arb sepolia → ethereum sepolia L1; else mainnet. */
    fun l1Env(env: String): String
    {
        val e = env.trim().lowercase()
        return if (e == "sepolia" || e == "testnet") "sepolia" else "mainnet"
    }

    fun normalizeSnapshotFlavor(raw: String?): String
    {
        return when (raw?.trim()?.lowercase())
        {
            "archive" -> "archive"
            else -> "pruned"
        }
    }
}
