package rpcnode.toolkit.nodes.application.snapshot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.domain.model.NodeDiskLayout

private val rawLayoutJson = Json { ignoreUnknownKeys = true }

/** Target directory on the host where chain snapshot data lands (from saved disk layout). */
internal fun snapshotDestDir(rawLayoutJson: String): String?
{
    decodeNodeDiskLayout(rawLayoutJson)?.let { return snapshotDestDir(it) }
    return snapshotDestDirFromRawJson(rawLayoutJson)
}

private fun snapshotDestDirFromRawJson(raw: String): String?
{
    if (raw.isBlank())
    {
        return null
    }
    val root = runCatching { rawLayoutJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    root.stringField("ledger_dir")?.let { return it }
    root.stringField("accounts_dir")?.let { return it }
    root.stringField("snapshots_dir")?.let { return it }
    val roles = root["roles"]?.jsonObject ?: return null
    roles.stringField("fullnode", "dir")?.let { return it }
    roles.stringField("execution", "dir")?.let { return it }
    roles.stringField("state", "dir")?.let { return it }
    roles.stringField("chaindata", "dir")?.let { return it }
    roles.stringField("bor", "dir")?.let { return it }
    roles.stringField("ledger", "dir")?.let { return it }
    roles.stringField("accounts", "dir")?.let { return it }
    roles.stringField("snapshots", "dir")?.let { return it }
    for ((_, value) in roles)
    {
        value.jsonObject.stringField("dir")?.let { return it }
    }
    return null
}

private fun JsonObject.stringField(vararg path: String): String?
{
    var current: JsonObject = this
    for (i in 0 until path.size - 1)
    {
        current = current[path[i]]?.jsonObject ?: return null
    }
    return current[path.last()]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
}

internal fun snapshotDestDir(layout: NodeDiskLayout): String?
{
    // Prefer chain data / client-sync leaves over Agave --snapshots (archive only).
    layout.roles.firstOrNull { it.id == "fullnode" && it.dir.isNotBlank() }?.dir?.let { return it }
    layout.roles.firstOrNull { it.id == "execution" && it.dir.isNotBlank() }?.dir?.let { return it }
    layout.roles.firstOrNull { it.id == "state" && it.dir.isNotBlank() }?.dir?.let { return it }
    layout.roles.firstOrNull { it.id == "chaindata" && it.dir.isNotBlank() }?.dir?.let { return it }
    layout.roles.firstOrNull { it.id == "bor" && it.dir.isNotBlank() }?.dir?.let { return it }
    layout.roles.firstOrNull { it.id == "ledger" && it.dir.isNotBlank() }?.dir?.let { return it }
    layout.ledgerDir.trim().takeIf { it.isNotBlank() }?.let { return it }
    layout.stateDir.trim().takeIf { it.isNotBlank() }?.let { return it }
    layout.roles.firstOrNull { it.id == "accounts" && it.dir.isNotBlank() }?.dir?.let { return it }
    layout.accountsDir.trim().takeIf { it.isNotBlank() }?.let { return it }
    layout.snapshotsDir.trim().takeIf { it.isNotBlank() }?.let { return it }
    layout.roles.firstOrNull { it.id == "snapshots" && it.dir.isNotBlank() }?.dir?.let { return it }
    return layout.roles.firstOrNull { it.dir.isNotBlank() }?.dir?.trim()?.takeIf { it.isNotBlank() }
}
