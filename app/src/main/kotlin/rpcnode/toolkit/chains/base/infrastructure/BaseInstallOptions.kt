package rpcnode.toolkit.chains.base.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Reads install_options.snapshot (archive|full|minimal). */
fun baseSnapshotFlavor(installOptionsJson: String?): String
{
    val raw = installOptionsJson?.trim().orEmpty()
    if (raw.isEmpty())
    {
        return BaseClusters.normalizeSnapshotFlavor(null)
    }
    val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return BaseClusters.normalizeSnapshotFlavor(null)
    val snap = root["snapshot"]?.jsonPrimitive?.contentOrNull
    return BaseClusters.normalizeSnapshotFlavor(snap)
}

/** L1 parent URLs from Start install_options, falling back to public defaults. */
fun baseL1FromInstallOptions(installOptionsJson: String?, env: String): BaseL1Parent.Endpoints
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
    return when (val resolved = BaseL1Parent.resolve(env, rpc, beacon))
    {
        is BaseL1Parent.Result.Ok -> resolved.endpoints
        is BaseL1Parent.Result.Missing ->
            BaseL1Parent.publicDefaults(env)
                ?: BaseL1Parent.Endpoints(rpc = rpc, beacon = beacon)
    }
}
