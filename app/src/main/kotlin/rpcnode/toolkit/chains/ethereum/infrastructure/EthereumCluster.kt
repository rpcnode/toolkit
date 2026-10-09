package rpcnode.toolkit.chains.ethereum.infrastructure

/** Static EL/CL facts for one env (checkpoint + geth/lighthouse flags). */
data class EthereumCluster(
    val env: String,
    val gethFlag: String,
    val lighthouseNetwork: String,
    val checkpointUrl: String,
    val chainId: String,
    val historyPostMerge: Boolean,
)

object EthereumClusters
{
    fun lookup(env: String): EthereumCluster
    {
        return when (env.trim().lowercase())
        {
            "sepolia" -> EthereumCluster(
                env = "sepolia",
                gethFlag = "--sepolia",
                lighthouseNetwork = "sepolia",
                checkpointUrl = "https://checkpoint-sync.sepolia.ethpandaops.io",
                chainId = "11155111",
                historyPostMerge = false,
            )
            "hoodi" -> EthereumCluster(
                env = "hoodi",
                gethFlag = "--hoodi",
                lighthouseNetwork = "hoodi",
                checkpointUrl = "https://checkpoint-sync.hoodi.ethpandaops.io",
                chainId = "560048",
                historyPostMerge = false,
            )
            else -> EthereumCluster(
                env = "mainnet",
                gethFlag = "",
                lighthouseNetwork = "mainnet",
                checkpointUrl = "https://sync-mainnet.beaconcha.in",
                chainId = "1",
                historyPostMerge = true,
            )
        }
    }

    fun cacheMb(env: String): Int =
        when (env.trim().lowercase())
        {
            "sepolia", "hoodi" -> 2048
            else -> 4096
        }

    fun normalizeMode(raw: String?): String
    {
        return when (raw?.trim()?.lowercase())
        {
            "archive" -> "archive"
            else -> "full"
        }
    }
}
