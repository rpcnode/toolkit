package rpcnode.toolkit.chains.ton.infrastructure

/** Env / chain flags for Toncoin liteserver. */
object TonClusters
{
    const val INSTALL_URL =
        "https://raw.githubusercontent.com/ton-blockchain/mytonctrl/master/scripts/install.sh"

    fun normalizeEnv(raw: String): String
    {
        val e = raw.trim().lowercase()
        return when (e)
        {
            "testnet" -> "testnet"
            else -> "mainnet"
        }
    }

    fun chainFlag(env: String): String = normalizeEnv(env)

    data class Cluster(
        val env: String,
        val chainFlag: String,
        val httpPort: Int,
        val p2pPort: Int,
    )

    fun lookup(env: String): Cluster
    {
        val e = normalizeEnv(env)
        val ports = TonPorts.forEnv(e)
        return Cluster(
            env = e,
            chainFlag = chainFlag(e),
            httpPort = ports.http,
            p2pPort = ports.p2p,
        )
    }
}
