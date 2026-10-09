package rpcnode.toolkit.chains.sui.infrastructure

/** Env + checkpoint archive / genesis helpers for Sui. */
data class SuiCluster(
    val env: String,
    val archiveUrl: String,
    val genesisUrl: String,
    val graphQlTipUrl: String,
)

object SuiClusters
{
    private val MAINNET = SuiCluster(
        env = "mainnet",
        archiveUrl = "https://checkpoints.mainnet.sui.io",
        genesisUrl = "https://github.com/MystenLabs/sui-genesis/raw/main/mainnet/genesis.blob",
        graphQlTipUrl = "https://graphql.mainnet.sui.io/graphql",
    )
    private val TESTNET = SuiCluster(
        env = "testnet",
        archiveUrl = "https://checkpoints.testnet.sui.io",
        genesisUrl = "https://github.com/MystenLabs/sui-genesis/raw/main/testnet/genesis.blob",
        graphQlTipUrl = "https://graphql.testnet.sui.io/graphql",
    )

    fun normalizeEnv(raw: String): String
    {
        val e = raw.trim().lowercase()
        return if (e == "testnet") "testnet" else "mainnet"
    }

    fun lookup(raw: String): SuiCluster =
        if (normalizeEnv(raw) == "testnet") TESTNET else MAINNET
}
