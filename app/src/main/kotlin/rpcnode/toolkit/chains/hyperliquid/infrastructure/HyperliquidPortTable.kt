package rpcnode.toolkit.chains.hyperliquid.infrastructure

/** Catalog-fixed ports (binary listens on these; same for both envs — oneEnvPerHost). */
data class HyperliquidPorts(
    val http: Int,
    val p2p: Int,
    val p2p2: Int,
)

object HyperliquidPortTable
{
    private val PORTS = HyperliquidPorts(http = 3001, p2p = 4001, p2p2 = 4002)

    fun forEnv(env: String): HyperliquidPorts
    {
        HyperliquidClusters.normalizeEnv(env)
        return PORTS
    }
}
