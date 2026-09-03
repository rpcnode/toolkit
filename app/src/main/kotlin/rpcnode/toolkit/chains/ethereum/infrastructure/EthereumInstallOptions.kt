package rpcnode.toolkit.chains.ethereum.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Reads install_options.node (full|archive). */
fun ethereumNodeMode(installOptionsJson: String?): String
{
    val raw = installOptionsJson?.trim().orEmpty()
    if (raw.isEmpty())
    {
        return EthereumClusters.normalizeMode(null)
    }
    val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return EthereumClusters.normalizeMode(null)
    val node = root["node"]?.jsonPrimitive?.contentOrNull
    return EthereumClusters.normalizeMode(node)
}
