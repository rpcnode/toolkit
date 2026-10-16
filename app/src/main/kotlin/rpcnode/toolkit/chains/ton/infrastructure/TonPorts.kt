package rpcnode.toolkit.chains.ton.infrastructure

/** Catalog-fixed ports — must match `chains/ton/clients.yml`. */
object TonPorts
{
    /**
     * [indexer] = v3 API of the indexer; [indexerRedis] = its own Redis (loopback only: an internal cache); [indexerPg] = the node's own
     * PostgreSQL cluster (loopback only). A node installed before dedicated clusters keeps its role/database in the host's shared
     * server (found by the install script) and does not use [indexerPg].
     * [publicApi] = the agent's router (v2 + v3 on one port).
     */
    data class Ports(
        val http: Int,
        val p2p: Int,
        val indexer: Int,
        val indexerRedis: Int,
        val indexerPg: Int,
        val publicApi: Int,
    )

    fun forEnv(env: String): Ports
    {
        return when (TonClusters.normalizeEnv(env))
        {
            "testnet" -> Ports(http = 8082, p2p = 31311, indexer = 8188, indexerRedis = 16388, indexerPg = 15433, publicApi = 8088)
            else -> Ports(http = 8081, p2p = 31310, indexer = 8187, indexerRedis = 16387, indexerPg = 15432, publicApi = 8087)
        }
    }
}
