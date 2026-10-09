package rpcnode.toolkit.chains.polygon.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Reads install_options.node (full|archive). */
fun polygonNodeMode(installOptionsJson: String?): String
{
    val raw = installOptionsJson?.trim().orEmpty()
    if (raw.isEmpty())
    {
        return PolygonClusters.normalizeMode(null)
    }
    val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return PolygonClusters.normalizeMode(null)
    val node = root["node"]?.jsonPrimitive?.contentOrNull
    return PolygonClusters.normalizeMode(node)
}
