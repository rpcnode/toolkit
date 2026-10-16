package rpcnode.toolkit.chains.starknet.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The install options a Starknet node reads from the saved wizard JSON. */
data class StarknetOptions(
    /** `pruned` | `full` — which official snapshot the DB came from. */
    val snapshot: String,
    /** Operator's `prune_blocks` ("" = not set). */
    val pruneBlocks: String,
    /** `--eth-node` WebSocket ("" = use the env's public default). */
    val ethNode: String,
)
{
    /**
     * `--prune-mode` window, or null for no pruning. A pruned snapshot runs with pruning on (the upstream docs start it with
     * `--prune-mode`), a full snapshot keeps everything unless the operator asks for a window. Pruning is irreversible.
     */
    fun pruneWindow(): String? =
        pruneBlocks.takeIf { it.isNotEmpty() }
            ?: StarknetChain.DEFAULT_PRUNE_WINDOW.takeIf { snapshot == StarknetChain.SNAPSHOT_PRUNED }

    companion object
    {
        private val json = Json { ignoreUnknownKeys = true }
        private val DIGITS = Regex("^[0-9]{1,12}$")

        fun parse(installOptionsJson: String?): StarknetOptions
        {
            val raw = installOptionsJson?.trim().orEmpty()
            val root: JsonObject? = if (raw.isEmpty()) null else runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            fun text(key: String): String = root?.get(key)?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            return StarknetOptions(
                snapshot = StarknetChain.normalizeSnapshot(text("snapshot")),
                // anything that is not a plain number is dropped: the value lands in the unit file
                pruneBlocks = text("prune_blocks").takeIf { DIGITS.matches(it) }.orEmpty(),
                ethNode = text("l1_rpc"),
            )
        }
    }
}
