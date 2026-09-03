package rpcnode.toolkit.chains.base.infrastructure

/** Catalog-fixed ports (clients/base.yml) — starter uses the same numbers. */
data class BasePorts(
    val p2p: Int,
    val http: Int,
    val ws: Int,
    val engine: Int,
    val consensusP2p: Int,
    val discoveryV5: Int,
)

object BasePortTable
{
    fun forEnv(env: String): BasePorts
    {
        return when (env.trim().lowercase())
        {
            "sepolia" -> BasePorts(
                p2p = 30354,
                http = 8573,
                ws = 8583,
                engine = 8574,
                consensusP2p = 9033,
                discoveryV5 = 9213,
            )
            else -> BasePorts(
                p2p = 30353,
                http = 8571,
                ws = 8581,
                engine = 8572,
                consensusP2p = 9023,
                discoveryV5 = 9203,
            )
        }
    }
}
