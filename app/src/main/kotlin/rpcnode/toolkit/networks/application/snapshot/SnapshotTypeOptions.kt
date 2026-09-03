package rpcnode.toolkit.networks.application.snapshot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.SnapshotTypeFacts
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository

private val optsJson = Json { ignoreUnknownKeys = true }

/** Read `install_options.snapshot` (wizard type id). */
fun snapshotTypeFromInstallOptions(rawJson: String): String?
{
    if (rawJson.isBlank())
    {
        return null
    }
    val root = runCatching { optsJson.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return null
    return root["snapshot"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
}

fun defaultSnapshotType(
    facts: NetworkFactsRepository,
    network: NetworkId,
    envRaw: String,
): String?
{
    val types = facts.factsFor(network)?.envs?.firstOrNull { it.id == envRaw.trim().lowercase() }?.snapshotTypes
        ?: return null
    return types.firstOrNull { it.default }?.id ?: types.firstOrNull()?.id
}

fun snapshotTypesFor(
    facts: NetworkFactsRepository,
    network: NetworkId,
    envRaw: String,
): List<SnapshotTypeFacts> =
    facts.factsFor(network)?.envs?.firstOrNull { it.id == envRaw.trim().lowercase() }?.snapshotTypes.orEmpty()

fun mergeInstallOptionsSnapshot(existingJson: String, typeId: String): String
{
    val type = typeId.trim().lowercase()
    val root = if (existingJson.isBlank())
    {
        emptyMap()
    }
    else
    {
        runCatching {
            optsJson.parseToJsonElement(existingJson).jsonObject.mapValues { it.value }
        }.getOrElse { emptyMap() }
    }.toMutableMap()
    root["snapshot"] = kotlinx.serialization.json.JsonPrimitive(type)
    return optsJson.encodeToString(JsonObject(root))
}
