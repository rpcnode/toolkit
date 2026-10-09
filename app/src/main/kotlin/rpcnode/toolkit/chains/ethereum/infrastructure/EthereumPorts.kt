package rpcnode.toolkit.chains.ethereum.infrastructure

/** Catalog-fixed ports (clients/ethereum.yml) — starter uses the same numbers. */
data class EthereumPorts(
    val p2p: Int,
    val http: Int,
    val engine: Int,
    val beacon: Int,
    val consensusP2p: Int,
)

object EthereumPortTable
{
    fun forEnv(env: String): EthereumPorts
    {
        return when (env.trim().lowercase())
        {
            "sepolia" -> EthereumPorts(p2p = 30313, http = 8546, engine = 8552, beacon = 5053, consensusP2p = 9100)
            "hoodi" -> EthereumPorts(p2p = 30323, http = 8547, engine = 8553, beacon = 5054, consensusP2p = 9200)
            else -> EthereumPorts(p2p = 30303, http = 8545, engine = 8551, beacon = 5052, consensusP2p = 9000)
        }
    }
}
