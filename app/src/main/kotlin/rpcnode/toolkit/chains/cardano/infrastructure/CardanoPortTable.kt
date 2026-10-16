package rpcnode.toolkit.chains.cardano.infrastructure

/**
 * Catalog-fixed ports (clients.yml): node-to-node P2P and the Prometheus endpoint (loopback), plus the optional indexer
 * companions: [ogmios] (JSON-RPC / WebSocket, loopback) and [dbSyncMetrics] (db-sync's Prometheus; the product binds it on all
 * interfaces, the unit confines it to loopback).
 */
data class CardanoPorts(val p2p: Int, val metrics: Int, val ogmios: Int, val dbSyncMetrics: Int)

object CardanoPortTable
{
    fun forEnv(env: String): CardanoPorts =
        when (CardanoClusters.normalizeEnv(env))
        {
            "preprod" -> CardanoPorts(p2p = 41222, metrics = 41224, ogmios = 41220, dbSyncMetrics = 41227)
            "preview" -> CardanoPorts(p2p = 41232, metrics = 41234, ogmios = 41230, dbSyncMetrics = 41237)
            else -> CardanoPorts(p2p = 41212, metrics = 41214, ogmios = 41210, dbSyncMetrics = 41217)
        }
}
