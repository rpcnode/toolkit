package rpcnode.toolkit.chains.arb.infrastructure

/** Catalog-fixed ports (clients/arb.yml) — starter uses the same numbers. */
data class ArbPorts(
    val http: Int,
    val ws: Int,
)

object ArbPortTable
{
    fun forEnv(env: String): ArbPorts
    {
        return when (env.trim().lowercase())
        {
            "sepolia" -> ArbPorts(http = 8657, ws = 8658)
            else -> ArbPorts(http = 8547, ws = 8548)
        }
    }
}
