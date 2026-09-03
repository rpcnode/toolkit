package rpcnode.toolkit.chains.bsc.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Reads install_options.snapshot (pruned|full). */
fun bscSnapshotFlavor(installOptionsJson: String?): String
{
    val raw = installOptionsJson?.trim().orEmpty()
    if (raw.isEmpty())
    {
        return BscClusters.normalizeSnapshotFlavor(null)
    }
    val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return BscClusters.normalizeSnapshotFlavor(null)
    val snap = root["snapshot"]?.jsonPrimitive?.contentOrNull
    return BscClusters.normalizeSnapshotFlavor(snap)
}
