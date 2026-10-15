package rpcnode.toolkit.chains.sui.infrastructure

/** Catalog-fixed ports per env (from Go facts / clients.yml). */
data class SuiPorts(
    val http: Int,
    val metrics: Int,
    val p2p: Int,
)

object SuiPortTable
{
    fun forEnv(env: String): SuiPorts
    {
        return when (SuiClusters.normalizeEnv(env))
        {
            "testnet" -> SuiPorts(http = 9410, metrics = 9185, p2p = 8185)
            else -> SuiPorts(http = 9400, metrics = 9184, p2p = 8084)
        }
    }
}
