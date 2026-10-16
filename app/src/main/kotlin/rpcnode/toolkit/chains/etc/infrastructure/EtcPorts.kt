package rpcnode.toolkit.chains.etc.infrastructure

/** Catalog-fixed ports (clients.yml) — the unit uses the same numbers. */
data class EtcPorts(
    val p2p: Int,
    val http: Int,
    val ws: Int,
)

object EtcPortTable
{
    fun forEnv(env: String): EtcPorts
    {
        return when (env.trim().lowercase())
        {
            "mordor" -> EtcPorts(p2p = 41402, http = 41400, ws = 41401)
            else -> EtcPorts(p2p = 41392, http = 41390, ws = 41391)
        }
    }
}
