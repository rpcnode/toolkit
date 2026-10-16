package rpcnode.toolkit.chains.stellar.infrastructure

/** Catalog-fixed ports (clients.yml) — config and unit use the same numbers. */
data class StellarPorts(val http: Int, val p2p: Int, val coreQuery: Int, val admin: Int)

object StellarPortTable
{
    fun forEnv(env: String): StellarPorts =
        if (StellarClusters.normalizeEnv(env) == "testnet")
        {
            StellarPorts(http = 42570, p2p = 42572, coreQuery = 42573, admin = 42574)
        }
        else
        {
            StellarPorts(http = 42560, p2p = 42562, coreQuery = 42563, admin = 42564)
        }
}
