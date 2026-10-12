package rpcnode.toolkit.chains.ton.infrastructure

/** Catalog-fixed ports — must match `chains/ton/clients.yml`. */
object TonPorts
{
    /**
     * [indexer] = loopback v3 API of the indexer; [indexerRedis] = its own Redis (loopback). The database is the host's
     * PostgreSQL (its own port, found by the install script), so it has no catalog port.
     * [publicApi] = the agent's router (v2 + v3 on one port).
     */
    data class Ports(
        val http: Int,
        val p2p: Int,
        val indexer: Int,
        val indexerRedis: Int,
        val publicApi: Int,
    )

    fun forEnv(env: String): Ports
    {
        return when (TonClusters.normalizeEnv(env))
        {
            "testnet" -> Ports(http = 8082, p2p = 31311, indexer = 8188, indexerRedis = 16388, publicApi = 8088)
            else -> Ports(http = 8081, p2p = 31310, indexer = 8187, indexerRedis = 16387, publicApi = 8087)
        }
    }
}
