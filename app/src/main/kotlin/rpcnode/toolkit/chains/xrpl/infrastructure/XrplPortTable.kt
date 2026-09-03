package rpcnode.toolkit.chains.xrpl.infrastructure

/** Catalog-fixed ports per env (from Go facts / clients.yml). */
data class XrplPorts(
    val http: Int,
    val p2p: Int,
    val ws: Int,
    val grpc: Int,
    /** Local admin WebSocket (cfg pin, not catalog). */
    val wsAdmin: Int,
)

object XrplPortTable
{
    fun forEnv(env: String): XrplPorts
    {
        return when (XrplClusters.normalizeEnv(env))
        {
            "testnet" -> XrplPorts(http = 5006, p2p = 51236, ws = 6008, grpc = 51252, wsAdmin = 6007)
            else -> XrplPorts(http = 5005, p2p = 51235, ws = 6005, grpc = 51251, wsAdmin = 6006)
        }
    }
}
