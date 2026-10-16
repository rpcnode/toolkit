package rpcnode.toolkit.chains.aptos.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Install option `bootstrap`: fast (latest state, pruned) or genesis (full history, pruners off). */
object AptosBootstrapMode
{
    const val GROUP = "bootstrap"
    const val FAST = "fast"
    const val GENESIS = "genesis"

    fun normalize(raw: String?): String = if (raw?.trim()?.lowercase() == GENESIS) GENESIS else FAST

    fun fromJson(installOptionsJson: String?): String
    {
        val raw = installOptionsJson?.trim().orEmpty()
        if (raw.isEmpty())
        {
            return FAST
        }
        val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return FAST
        return normalize(root[GROUP]?.jsonPrimitive?.contentOrNull)
    }
}
