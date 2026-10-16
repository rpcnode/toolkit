package rpcnode.toolkit.chains.arc.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ArcCluster(
    val env: String,
    /** `--chain` value of arc-node-execution and arc-snapshots. */
    val chain: String,
    /** Relay endpoints the consensus layer follows (`--follow.endpoint`, format `https://host,wss=host`). */
    val relays: List<String>,
    /** `--rpc.forwarder`: where submitted transactions are routed. */
    val forwarder: String,
)

object ArcClusters
{
    private val MAINNET = ArcCluster(
        env = "mainnet",
        chain = "arc-mainnet",
        relays = listOf(
            "https://rpc.mainnet.arc.io,wss=rpc.mainnet.arc.io",
            "https://rpc.drpc.mainnet.arc.io,wss=rpc.drpc.mainnet.arc.io",
            "https://rpc.blockdaemon.mainnet.arc.io,wss=rpc.blockdaemon.mainnet.arc.io/websocket",
            "https://rpc.quicknode.mainnet.arc.io,wss=rpc.quicknode.mainnet.arc.io",
        ),
        forwarder = "https://rpc.mainnet.arc.io/",
    )
    private val TESTNET = ArcCluster(
        env = "testnet",
        chain = "arc-testnet",
        relays = listOf(
            "https://rpc.testnet.arc.io,wss=rpc.testnet.arc.io",
            "https://rpc.drpc.testnet.arc.io,wss=rpc.drpc.testnet.arc.io",
            "https://rpc.blockdaemon.testnet.arc.io,wss=rpc.blockdaemon.testnet.arc.io/websocket",
        ),
        forwarder = "https://rpc.testnet.arc.io/",
    )

    fun normalizeEnv(raw: String): String = if (raw.trim().lowercase() == "testnet") "testnet" else "mainnet"

    fun lookup(raw: String): ArcCluster = if (normalizeEnv(raw) == "testnet") TESTNET else MAINNET
}

/** Catalog-fixed ports per env — must match `chains/arc/clients.yml`. */
data class ArcPorts(val http: Int, val ws: Int, val elMetrics: Int, val clRpc: Int, val clMetrics: Int)

object ArcPortTable
{
    fun forEnv(env: String): ArcPorts
    {
        return when (ArcClusters.normalizeEnv(env))
        {
            "testnet" -> ArcPorts(http = 8592, ws = 8594, elMetrics = 9292, clRpc = 31092, clMetrics = 29092)
            else -> ArcPorts(http = 8591, ws = 8593, elMetrics = 9291, clRpc = 31091, clMetrics = 29091)
        }
    }
}

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Node type: full (pruned, default) or archive. It is the wizard's snapshot type, saved as install option `snapshot`. */
object ArcNodeMode
{
    const val GROUP = "snapshot"
    const val FULL = "full"
    const val ARCHIVE = "archive"

    fun normalize(raw: String?): String = if (raw?.trim()?.lowercase() == ARCHIVE) ARCHIVE else FULL

    fun fromJson(installOptionsJson: String?): String
    {
        val raw = installOptionsJson?.trim().orEmpty()
        if (raw.isEmpty())
        {
            return FULL
        }
        val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return FULL
        // `snapshot` is what the wizard saves; `node` is accepted for options saved by an earlier build
        return normalize(root[GROUP]?.jsonPrimitive?.contentOrNull ?: root["node"]?.jsonPrimitive?.contentOrNull)
    }
}
