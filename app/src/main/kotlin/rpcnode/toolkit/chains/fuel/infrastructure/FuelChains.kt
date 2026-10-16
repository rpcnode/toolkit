package rpcnode.toolkit.chains.fuel.infrastructure

import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmUnit

/**
 * One env of Fuel Ignition. The relayer must read the matching Ethereum network (mainnet → Ethereum mainnet, testnet →
 * Sepolia): a wrong-network endpoint makes it fail to start. Values: docs.fuel.network node-operator mainnet / testnet pages.
 */
data class FuelEnv(
    val id: String,
    val basePort: Int,
    /** Folder of the chain config on `chain-config-assets.fuel.network` (FuelLabs/chain-configuration). */
    val configDir: String,
    val bootstrap: String,
    val portalContract: String,
    val daDeployHeight: Long,
    val logPageSize: Int,
    val defaultL1Rpc: String,
    val publicTipUrls: List<String>,
)
{
    val graphqlPort: Int get() = basePort
    val p2pPort: Int get() = basePort + 2
}

object FuelChains
{
    val MAINNET = FuelEnv(
        id = "mainnet",
        basePort = 41540,
        configDir = "ignition",
        bootstrap = "/dnsaddr/mainnet.fuel.network",
        portalContract = "0xAEB0c00D0125A8a788956ade4f4F12Ead9f65DDf",
        daDeployHeight = 20_620_434,
        logPageSize = 100,
        defaultL1Rpc = "https://ethereum-rpc.publicnode.com",
        publicTipUrls = listOf("https://mainnet.fuel.network/v1/graphql"),
    )
    val TESTNET = FuelEnv(
        id = "testnet",
        basePort = 41550,
        configDir = "ignition-test",
        bootstrap = "/dnsaddr/testnet.fuel.network",
        portalContract = "0x01855B78C1f8868DE70e84507ec735983bf262dA",
        daDeployHeight = 5_827_607,
        logPageSize = 500,
        defaultL1Rpc = "https://ethereum-sepolia-rpc.publicnode.com",
        publicTipUrls = listOf("https://testnet.fuel.network/v1/graphql"),
    )
    val ALL = listOf(MAINNET, TESTNET)

    fun env(raw: String): FuelEnv = ALL.firstOrNull { it.id == raw.trim().lowercase() } ?: MAINNET

    /** The four files of a chain config, synced flat into the node dir and copied into `<node_dir>/chain/` by Start. */
    val CHAIN_CONFIG_FILES = listOf("metadata.json", "chain_config.json", "state_config.json", "state_transition_bytecode.wasm")
}

/** Literals of `fuel-core run` (`network.yml` literal bindings, kept equal by `FuelCatalogTest`). Vendor defaults unless noted. */
object FuelSettings
{
    const val SERVICE_NAME = "rpcnode-fuel"
    const val SYNC_HEADER_BATCH_SIZE = 50
    const val SYNC_BLOCK_STREAM_BUFFER = 30
    const val GRAPHQL_THREADS = 2
    const val GRAPHQL_MAX_CONCURRENT_QUERIES = 1024
    const val GRAPHQL_REQUEST_BODY_BYTES = 1_048_576
    const val API_REQUEST_TIMEOUT = "30s"
    const val MAX_PEERS_CONNECTED = 50
    const val NOFILE = 1_048_576
    const val RELATIVE_LOG = "logs/fuel.log"
    const val ENV_FILE = ".toolkit/fuel.env"
    const val KEY_FILE = ".toolkit/fuel-p2p.key"
}

object FuelUnitBodies
{
    /**
     * `fuel-core run`. Never `--debug`; never `--rpc-port` (gRPC, default 4001, stays off); metrics are served on the GraphQL
     * port (`/v1/metrics`), so that port belongs behind a proxy that blocks the path. `--poa-instant` defaults to TRUE upstream:
     * it must be passed `false`. The libp2p secret and the L1 URL come from the 0600 env file (`KEYPAIR`, `RELAYER`).
     */
    fun flags(env: FuelEnv, dbPath: String, snapshotDir: String): List<String> = listOf(
        "--service-name=${FuelSettings.SERVICE_NAME}",
        "--ip=0.0.0.0",
        "--port=${env.graphqlPort}",
        "--peering-port=${env.p2pPort}",
        "--db-path=$dbPath",
        "--snapshot=$snapshotDir",
        "--utxo-validation",
        "--poa-instant=false",
        "--enable-p2p",
        "--bootstrap-nodes=${env.bootstrap}",
        "--sync-header-batch-size=${FuelSettings.SYNC_HEADER_BATCH_SIZE}",
        "--sync-block-stream-buffer-size=${FuelSettings.SYNC_BLOCK_STREAM_BUFFER}",
        "--enable-relayer",
        "--relayer-v2-listening-contracts=${env.portalContract}",
        "--relayer-da-deploy-height=${env.daDeployHeight}",
        "--relayer-log-page-size=${env.logPageSize}",
        "--graphql-number-of-threads=${FuelSettings.GRAPHQL_THREADS}",
        "--graphql-max-concurrent-queries=${FuelSettings.GRAPHQL_MAX_CONCURRENT_QUERIES}",
        "--graphql-request-body-bytes-limit=${FuelSettings.GRAPHQL_REQUEST_BODY_BYTES}",
        "--api-request-timeout=${FuelSettings.API_REQUEST_TIMEOUT}",
        "--max-peers-connected=${FuelSettings.MAX_PEERS_CONNECTED}",
    )

    fun unit(env: FuelEnv, nodeDir: String, binary: String, dbPath: String, logFile: String): String =
        NonEvmUnit.render(
            network = "fuel",
            description = "Fuel Ignition (${env.id}) — RpcNode",
            nodeDir = nodeDir,
            execStart = NonEvmUnit.execStart("$binary run", flags(env, dbPath, "$nodeDir/chain")),
            logFile = logFile,
            nofile = FuelSettings.NOFILE,
        )
}

/** The 0600 environment file of the unit: secrets stay out of argv, `ps` and the world-readable unit. */
object FuelEnvFile
{
    private val SECRET = Regex("^[0-9a-fA-F]{64}$")
    private val URL = Regex("""^https?://[^\s"'\\]+$""")

    fun validSecret(secret: String): Boolean = SECRET.matches(secret.trim())

    fun validUrl(url: String): Boolean = URL.matches(url.trim())

    fun render(keypairSecret: String, relayerUrl: String): String =
        "KEYPAIR=${keypairSecret.trim()}\nRELAYER=${relayerUrl.trim()}\n"

    /** `secret` of the `fuel-core-keygen new --key-type peering` JSON line. */
    fun secretFromKeygen(output: String): String? =
        Regex(""""secret"\s*:\s*"([0-9a-fA-F]{64})"""").find(output)?.groupValues?.get(1)
}
