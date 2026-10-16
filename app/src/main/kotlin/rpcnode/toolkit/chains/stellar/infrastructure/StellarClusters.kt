package rpcnode.toolkit.chains.stellar.infrastructure

/** Public AWS ledger data lake (anonymous S3) that stellar-rpc can backfill its retention window from. */
data class StellarDatastore(val bucketPath: String, val region: String)

data class StellarCluster(
    val env: String,
    val passphrase: String,
    val historyArchiveUrls: List<String>,
    /** Synced captive-core config artifact (clients.yml `configs`). */
    val captiveCoreConfig: String,
    /** Null where no public lake exists (testnet). */
    val datastore: StellarDatastore?,
)

object StellarClusters
{
    private val MAINNET = StellarCluster(
        env = "mainnet",
        passphrase = "Public Global Stellar Network ; September 2015",
        historyArchiveUrls = listOf(
            "https://history.stellar.org/prd/core-live/core_live_001/",
            "https://history.stellar.org/prd/core-live/core_live_002/",
            "https://history.stellar.org/prd/core-live/core_live_003/",
        ),
        captiveCoreConfig = "captive-core-pubnet.cfg",
        datastore = StellarDatastore("aws-public-blockchain/v1.1/stellar/ledgers/pubnet", "us-east-2"),
    )

    private val TESTNET = StellarCluster(
        env = "testnet",
        passphrase = "Test SDF Network ; September 2015",
        historyArchiveUrls = listOf(
            "https://history.stellar.org/prd/core-testnet/core_testnet_001/",
            "https://history.stellar.org/prd/core-testnet/core_testnet_002/",
            "https://history.stellar.org/prd/core-testnet/core_testnet_003/",
        ),
        captiveCoreConfig = "captive-core-testnet.cfg",
        datastore = null,
    )

    fun normalizeEnv(raw: String): String = if (raw.trim().lowercase() == "testnet") "testnet" else "mainnet"

    fun lookup(raw: String): StellarCluster = if (normalizeEnv(raw) == "testnet") TESTNET else MAINNET
}
