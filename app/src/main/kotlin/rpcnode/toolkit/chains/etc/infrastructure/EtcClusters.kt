package rpcnode.toolkit.chains.etc.infrastructure

/** Static facts of one Ethereum Classic env: the core-geth chain flag, ids and the geth cache size. */
data class EtcCluster(
    val env: String,
    val chainFlag: String,
    val chainId: String,
    val networkId: String,
    val cacheMb: Int,
)

object EtcClusters
{
    fun lookup(env: String): EtcCluster
    {
        return when (env.trim().lowercase())
        {
            "mordor" -> EtcCluster(env = "mordor", chainFlag = "--mordor", chainId = "63", networkId = "7", cacheMb = 1024)
            else -> EtcCluster(env = "mainnet", chainFlag = "--classic", chainId = "61", networkId = "1", cacheMb = 4096)
        }
    }

    fun normalizeMode(raw: String?): String =
        if (raw?.trim()?.lowercase() == "archive") "archive" else "full"
}
