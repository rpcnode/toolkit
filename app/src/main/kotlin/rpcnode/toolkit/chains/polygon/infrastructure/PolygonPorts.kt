package rpcnode.toolkit.chains.polygon.infrastructure

/** Catalog-fixed ports (clients/polygon.yml) — starter uses the same numbers. */
data class PolygonPorts(
    val p2p: Int,
    val http: Int,
    val ws: Int,
    val heimdallP2p: Int,
    val heimdallRpc: Int,
    val heimdallApi: Int,
)

object PolygonPortTable
{
    fun forEnv(env: String): PolygonPorts
    {
        return when (env.trim().lowercase())
        {
            "amoy" -> PolygonPorts(
                p2p = 30343,
                http = 8558,
                ws = 8559,
                heimdallP2p = 26756,
                heimdallRpc = 26757,
                heimdallApi = 1327,
            )
            else -> PolygonPorts(
                p2p = 30333,
                http = 8548,
                ws = 8549,
                heimdallP2p = 26656,
                heimdallRpc = 26657,
                heimdallApi = 1317,
            )
        }
    }
}
