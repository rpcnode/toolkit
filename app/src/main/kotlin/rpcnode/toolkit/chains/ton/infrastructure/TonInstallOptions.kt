package rpcnode.toolkit.chains.ton.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Install option group `history` — dump (~30d) vs archive. */
object TonInstallOptions
{
    const val GROUP = "history"
    const val DUMP = "dump"
    const val ARCHIVE = "archive"

    fun normalize(raw: String?): String
    {
        return when (raw?.trim()?.lowercase())
        {
            ARCHIVE -> ARCHIVE
            else -> DUMP
        }
    }

    fun fromJson(installOptionsJson: String?): String
    {
        val raw = installOptionsJson?.trim().orEmpty()
        if (raw.isEmpty())
        {
            return DUMP
        }
        val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return DUMP
        return normalize(root[GROUP]?.jsonPrimitive?.contentOrNull)
    }

    fun installExtra(mode: String): String =
        if (normalize(mode) == ARCHIVE) "--archive" else "-d"
}
