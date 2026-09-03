package rpcnode.toolkit.chains.ton.infrastructure

/** Catalog-fixed ports — must match `chains/ton/clients.yml`. */
object TonPorts
{
    data class Ports(val http: Int, val p2p: Int)

    fun forEnv(env: String): Ports
    {
        return when (TonClusters.normalizeEnv(env))
        {
            "testnet" -> Ports(http = 8082, p2p = 30311)
            else -> Ports(http = 8081, p2p = 30310)
        }
    }
}
