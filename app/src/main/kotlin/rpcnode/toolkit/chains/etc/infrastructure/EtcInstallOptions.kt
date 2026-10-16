package rpcnode.toolkit.chains.etc.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Reads install_options.node (full|archive). */
fun etcNodeMode(installOptionsJson: String?): String
{
    val raw = installOptionsJson?.trim().orEmpty()
    if (raw.isEmpty())
    {
        return EtcClusters.normalizeMode(null)
    }
    val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return EtcClusters.normalizeMode(null)
    return EtcClusters.normalizeMode(root["node"]?.jsonPrimitive?.contentOrNull)
}
