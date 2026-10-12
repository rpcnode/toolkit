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

    /** Official global config of the network; MyTonCtrl `install.sh -n <env>` only fetches it on a fresh host. */
    fun globalConfigUrl(env: String): String =
        if (normalizeEnv(env) == "testnet") "https://ton.org/testnet-global.config.json" else "https://ton.org/global-config.json"

    /** Root hash (hex, upper) of the zero state in that config — what tells the networks apart. */
    fun zeroStateHash(env: String): String =
        if (normalizeEnv(env) == "testnet")
        {
            "823F81F306FF02694F935CF5021548E3CE2B86B529812AF6A12148879E95A128"
        }
        else
        {
            "17A3A92992AABEA785A7A090985A265CD31F323D849DA51239737E321FB05569"
        }

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
