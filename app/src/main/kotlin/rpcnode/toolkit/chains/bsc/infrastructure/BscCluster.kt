package rpcnode.toolkit.chains.bsc.infrastructure

/** Static BSC facts for one env (Parlia — no separate consensus client). */
data class BscCluster(
    val env: String,
    val chainId: String,
    val zipAsset: String,
    val snapshotPrefix: String,
)

object BscClusters
{
    fun lookup(env: String): BscCluster
    {
        return when (env.trim().lowercase())
        {
            "testnet", "chapel" -> BscCluster(
                env = "testnet",
                chainId = "97",
                zipAsset = "testnet.zip",
                snapshotPrefix = "testnet-geth-pbss",
            )
            else -> BscCluster(
                env = "mainnet",
                chainId = "56",
                zipAsset = "mainnet.zip",
                snapshotPrefix = "mainnet-geth-pbss",
            )
        }
    }

    fun cacheMb(env: String): Int =
        when (env.trim().lowercase())
        {
            "testnet", "chapel" -> 4096
            else -> 8192
        }

    fun normalizeSnapshotFlavor(raw: String?): String
    {
        return when (raw?.trim()?.lowercase())
        {
            "full" -> "full"
            else -> "pruned"
        }
    }
}
