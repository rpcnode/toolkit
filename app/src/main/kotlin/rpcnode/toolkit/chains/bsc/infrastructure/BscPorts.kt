package rpcnode.toolkit.chains.bsc.infrastructure

/** Catalog-fixed ports (clients/bsc.yml) — starter uses the same numbers. */
data class BscPorts(
    val p2p: Int,
    val http: Int,
    val ws: Int,
)

object BscPortTable
{
    fun forEnv(env: String): BscPorts
    {
        return when (env.trim().lowercase())
        {
            "testnet", "chapel" -> BscPorts(p2p = 30312, http = 8576, ws = 8578)
            else -> BscPorts(p2p = 30311, http = 8575, ws = 8577)
        }
    }
}
