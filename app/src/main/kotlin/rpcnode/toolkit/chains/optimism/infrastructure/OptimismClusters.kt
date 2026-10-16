package rpcnode.toolkit.chains.optimism.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Static OP Stack facts for one env (`sepolia` = OP Sepolia, parent chain Ethereum Sepolia). */
data class OptimismCluster(
    val env: String,
    /** op-reth `--chain`. */
    val rethChain: String,
    /** op-node `--network`. */
    val nodeNetwork: String,
    val sequencer: String,
    /** `op-mainnet` / `op-sepolia` part of the data-directory snapshot file names. */
    val snapshotNet: String,
    /** OP Mainnet cannot start without the snapshot (pre-Bedrock state import); OP Sepolia only needs it for archive. */
    val snapshotAlways: Boolean,
)

object OptimismClusters
{
    private val MAINNET = OptimismCluster(
        env = "mainnet",
        rethChain = "optimism",
        nodeNetwork = "op-mainnet",
        sequencer = "https://mainnet-sequencer.optimism.io",
        snapshotNet = "mainnet",
        snapshotAlways = true,
    )
    private val SEPOLIA = OptimismCluster(
        env = "sepolia",
        rethChain = "optimism_sepolia",
        nodeNetwork = "op-sepolia",
        sequencer = "https://sepolia-sequencer.optimism.io",
        snapshotNet = "sepolia",
        snapshotAlways = false,
    )

    fun normalizeEnv(raw: String): String = if (raw.trim().lowercase() == "sepolia") "sepolia" else "mainnet"

    fun lookup(raw: String): OptimismCluster = if (normalizeEnv(raw) == "sepolia") SEPOLIA else MAINNET
}

/** Catalog-fixed ports per env — must match `chains/optimism/clients.yml`. */
data class OptimismPorts(
    val http: Int,
    val ws: Int,
    val engine: Int,
    val metrics: Int,
    val p2p: Int,
    val clRpc: Int,
    val clMetrics: Int,
    val clP2p: Int,
    /** op-reth discv5 (UDP). Its default 9200 is the same on every op-reth of a host: two nodes collide. */
    val discV5: Int,
)

object OptimismPortTable
{
    fun forEnv(env: String): OptimismPorts =
        if (OptimismClusters.normalizeEnv(env) == "sepolia")
            OptimismPorts(8564, 8565, 8566, 9264, 30364, 9564, 7364, 9364, 9464)
        else
            OptimismPorts(8561, 8562, 8563, 9261, 30361, 9561, 7361, 9361, 9461)
}

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Install option group `node` — full (pruned) or archive. */
object OptimismNodeMode
{
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
        return normalize(root["node"]?.jsonPrimitive?.contentOrNull)
    }
}
