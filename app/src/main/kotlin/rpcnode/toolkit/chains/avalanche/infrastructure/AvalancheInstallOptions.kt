package rpcnode.toolkit.chains.avalanche.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Install option group `node` — C-Chain mode. */
object AvalancheNodeMode
{
    const val GROUP = "node"
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
        return normalize(root[GROUP]?.jsonPrimitive?.contentOrNull)
    }
}
