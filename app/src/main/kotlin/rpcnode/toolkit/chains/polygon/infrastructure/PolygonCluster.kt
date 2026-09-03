package rpcnode.toolkit.chains.polygon.infrastructure

/** Static Bor/Heimdall facts for one env. */
data class PolygonCluster(
    val env: String,
    val chainId: String,
    /** CometBFT/Heimdall chain-id for `heimdalld init` / start. */
    val heimdallChainId: String,
    val borNetwork: String,
    val ethRpcUrl: String,
    val genesisFile: String,
)

object PolygonClusters
{
    fun lookup(env: String): PolygonCluster
    {
        return when (env.trim().lowercase())
        {
            "amoy" -> PolygonCluster(
                env = "amoy",
                chainId = "80002",
                heimdallChainId = "heimdallv2-80002",
                borNetwork = "amoy",
                ethRpcUrl = "https://ethereum-sepolia-rpc.publicnode.com",
                genesisFile = "genesis-amoy.json",
            )
            else -> PolygonCluster(
                env = "mainnet",
                chainId = "137",
                heimdallChainId = "heimdallv2-137",
                borNetwork = "mainnet",
                ethRpcUrl = "https://ethereum-rpc.publicnode.com",
                genesisFile = "genesis-mainnet.json",
            )
        }
    }

    fun normalizeMode(raw: String?): String
    {
        return when (raw?.trim()?.lowercase())
        {
            "archive" -> "archive"
            else -> "full"
        }
    }
}
