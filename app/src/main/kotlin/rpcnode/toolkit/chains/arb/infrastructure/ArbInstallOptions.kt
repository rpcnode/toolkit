package rpcnode.toolkit.chains.arb.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Reads install_options.snapshot (pruned|archive). */
fun arbSnapshotFlavor(installOptionsJson: String?): String
{
    val raw = installOptionsJson?.trim().orEmpty()
    if (raw.isEmpty())
    {
        return ArbClusters.normalizeSnapshotFlavor(null)
    }
    val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return ArbClusters.normalizeSnapshotFlavor(null)
    val snap = root["snapshot"]?.jsonPrimitive?.contentOrNull
    return ArbClusters.normalizeSnapshotFlavor(snap)
}

/** L1 parent URLs from Start install_options, falling back to public defaults. */
fun arbL1FromInstallOptions(installOptionsJson: String?, env: String): ArbL1Parent.Endpoints
{
    val raw = installOptionsJson?.trim().orEmpty()
    val root = if (raw.isEmpty())
    {
        null
    }
    else
    {
        runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
    }
    val rpc = root?.get("l1_rpc")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val beacon = root?.get("l1_beacon")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    return when (val resolved = ArbL1Parent.resolve(env, rpc, beacon))
    {
        is ArbL1Parent.Result.Ok -> resolved.endpoints
        is ArbL1Parent.Result.Missing ->
            ArbL1Parent.publicDefaults(env)
                ?: ArbL1Parent.Endpoints(rpc = rpc, beacon = beacon)
    }
}
