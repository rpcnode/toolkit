package rpcnode.toolkit.chains.aptos.infrastructure

object AptosClusters
{
    fun normalizeEnv(raw: String): String = if (raw.trim().lowercase() == "testnet") "testnet" else "mainnet"
}

/** Catalog-fixed ports (clients.yml); the generated fullnode.yaml uses the same numbers. */
data class AptosPorts(val http: Int, val p2p: Int, val metrics: Int, val admin: Int, val backup: Int)

object AptosPortTable
{
    fun forEnv(env: String): AptosPorts =
        if (AptosClusters.normalizeEnv(env) == "testnet")
        {
            AptosPorts(http = 41070, p2p = 41072, metrics = 41074, admin = 41077, backup = 41078)
        }
        else
        {
            AptosPorts(http = 41060, p2p = 41062, metrics = 41064, admin = 41067, backup = 41068)
        }
}
