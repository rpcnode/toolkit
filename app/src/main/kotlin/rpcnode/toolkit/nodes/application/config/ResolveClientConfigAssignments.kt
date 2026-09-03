package rpcnode.toolkit.nodes.application.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.clients.domain.model.ProgramPort
import rpcnode.toolkit.clients.domain.model.PortConfigPolicy
import rpcnode.toolkit.clients.domain.model.catalogPortConfigEnabled
import rpcnode.toolkit.clients.domain.model.isCatalogPortBindingSource
import rpcnode.toolkit.networks.domain.model.ClientConfigBindingFacts
import rpcnode.toolkit.networks.domain.model.ClientConfigFacts
import rpcnode.toolkit.networks.domain.model.SnapshotTypeFacts
import rpcnode.toolkit.nodes.application.snapshot.applySnapshotDestLeaf
import rpcnode.toolkit.nodes.domain.model.NodeDiskLayout
import rpcnode.toolkit.nodes.domain.model.DiskRolePlacement

private val optionsJson = Json { ignoreUnknownKeys = true }

/**
 * Resolves [ClientConfigFacts.bindings] into path → value for template patching.
 * [snapshotTypes] + install_options.snapshot drive `snapshot_kind` bindings (e.g. transHistory).
 */
fun resolveClientConfigAssignments(
    config: ClientConfigFacts,
    layout: NodeDiskLayout?,
    ports: List<ProgramPort>,
    installOptionsJson: String,
    snapshotTypes: List<SnapshotTypeFacts> = emptyList(),
): Map<String, String>
{
    val options = parseInstallOptions(installOptionsJson)
    val byRole = layout?.roles?.associateBy { it.id }.orEmpty()
    val byPortRole = ports
        .filter { it.role.isNotBlank() && it.port > 0 }
        .associateBy { it.role.lowercase() }
    val snapshotId = options["snapshot"]?.trim()?.lowercase().orEmpty()
    val snapshotKind = snapshotTypes.firstOrNull { it.id == snapshotId }?.kind?.trim()?.lowercase()
        ?.ifEmpty { null }
        ?: snapshotId.takeIf { it.isNotEmpty() }
    val out = linkedMapOf<String, String>()
    for (b in config.bindings)
    {
        if (b.source.trim().lowercase() == "disk_role_dir" && !shouldEmitDiskRoleDir(b, layout, byRole))
        {
            continue
        }
        if (!installOptionGateOpen(b, options))
        {
            continue
        }
        if (isCatalogPortBindingSource(b.source))
        {
            val role = b.role?.trim().orEmpty()
            if (role.isNotEmpty() && !catalogPortConfigEnabled(role, ports, options))
            {
                continue
            }
        }
        val value = resolveBinding(b, byRole, byPortRole, options, snapshotId, snapshotKind, snapshotTypes)
            ?: continue
        if (value.isBlank())
        {
            continue
        }
        out[b.path] = value
    }
    return out
}

/** INI keys to comment out — optional bindings that were skipped this render. */
fun resolveClientConfigOmitIniKeys(
    config: ClientConfigFacts,
    assignments: Map<String, String>,
    ports: List<ProgramPort> = emptyList(),
): Set<String>
{
    if (config.format.trim().lowercase() != "ini")
    {
        return emptySet()
    }
    return config.bindings
        .filter { b -> b.path !in assignments && shouldOmitConfigIniKey(b, ports) }
        .map { ClientConfigLeafPatch.iniLeafKey(it.path) }
        .toSet()
}

private fun shouldOmitConfigIniKey(
    binding: ClientConfigBindingFacts,
    ports: List<ProgramPort>,
): Boolean
{
    if (binding.optional && binding.source.trim().lowercase() == "disk_role_dir")
    {
        return true
    }
    if (isCatalogPortBindingSource(binding.source))
    {
        val role = binding.role?.trim().orEmpty()
        if (role.isNotEmpty())
        {
            val policy = ports.firstOrNull { it.role.equals(role, ignoreCase = true) }?.configPolicy
            if (policy == PortConfigPolicy.OPTIONAL || policy == PortConfigPolicy.NONE)
            {
                return true
            }
        }
    }
    return binding.optional
}

internal fun shouldEmitDiskRoleDir(
    binding: ClientConfigBindingFacts,
    layout: NodeDiskLayout?,
    byRole: Map<String, DiskRolePlacement>,
): Boolean
{
    if (!binding.optional)
    {
        return true
    }
    val roleId = binding.role?.trim().orEmpty()
    if (roleId.isEmpty())
    {
        return true
    }
    val role = byRole[roleId] ?: return false
    if (role.dir.isBlank() && role.mount.isBlank())
    {
        return false
    }
    // Reserved aux path (e.g. bitcoin blocksdir → index role): only when JBOD splits mounts.
    if (roleId == "index")
    {
        val mounts = layout?.roles
            ?.map { it.mount.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        if (mounts.size < 2)
        {
            return false
        }
        val blockchain = byRole["blockchain"]
        if (blockchain != null)
        {
            val chainMount = blockchain.mount.trim()
            val indexMount = role.mount.trim()
            if (chainMount.isNotEmpty() && indexMount.isNotEmpty() && chainMount == indexMount)
            {
                return false
            }
        }
    }
    return true
}

internal fun installOptionGateOpen(
    binding: ClientConfigBindingFacts,
    options: Map<String, String>,
): Boolean
{
    val key = binding.whenInstallOption?.trim()?.ifEmpty { null } ?: return true
    val want = binding.whenInstallOptionValue?.trim()?.ifEmpty { null } ?: "1"
    return options[key]?.trim() == want
}

fun clientConfigTemplateName(config: ClientConfigFacts, env: String): String?
{
    val fromMap = config.templates[env.trim().lowercase()]?.trim()?.ifEmpty { null }
    if (fromMap != null)
    {
        return fromMap
    }
    return config.template?.trim()?.ifEmpty { null }
}

/** INI section name for the env (networks YAML clientConfig.envSections). */
fun clientConfigIniSection(config: ClientConfigFacts, env: String): String?
{
    if (config.format.trim().lowercase() != "ini")
    {
        return null
    }
    return config.envSections[env.trim().lowercase()]?.trim()?.ifEmpty { null }
}

/** Config file next to the env data root: parent of the first role dir + template name. */
fun clientConfigDestPath(layout: NodeDiskLayout?, templateName: String): String?
{
    val dir = layout?.roles?.firstOrNull()?.dir?.trim()?.trimEnd('/') ?: return null
    val parent = dir.substringBeforeLast('/', missingDelimiterValue = "")
    if (parent.isBlank())
    {
        return null
    }
    return "$parent/${templateName.trim().trimStart('/')}"
}

private fun resolveBinding(
    b: ClientConfigBindingFacts,
    byRole: Map<String, DiskRolePlacement>,
    byPortRole: Map<String, ProgramPort>,
    options: Map<String, String>,
    snapshotId: String,
    snapshotKind: String?,
    snapshotTypes: List<SnapshotTypeFacts>,
): String?
{
    return when (b.source.trim().lowercase())
    {
        "disk_role_dir" ->
        {
            val role = byRole[b.role?.trim().orEmpty()] ?: return b.default
            val rawBase = role.dir.ifBlank { role.mount }
            // Lite (and destLeaf) rewrite …/fullnode → …/litefullnode so storage matches snapshot extract.
            val base = applySnapshotDestLeaf(rawBase, snapshotId.ifEmpty { null }, snapshotTypes)
            joinRelative(base, b.relative)
                .ifBlank { b.default }
        }
        "disk_role_mount" ->
        {
            val role = byRole[b.role?.trim().orEmpty()] ?: return b.default
            role.mount.ifBlank { b.default }
        }
        "catalog_port" ->
        {
            val port = byPortRole[b.role?.trim()?.lowercase().orEmpty()]
            if (port != null && port.port > 0) port.port.toString() else b.default
        }
        "catalog_zmq_bind" ->
        {
            val port = byPortRole[b.role?.trim()?.lowercase().orEmpty()]
            if (port != null && port.port > 0) "tcp://127.0.0.1:${port.port}" else b.default
        }
        "install_option" ->
        {
            val opt = b.option?.trim().orEmpty()
            options[opt]?.trim()?.ifEmpty { null } ?: b.default
        }
        "snapshot_kind" -> resolveSnapshotKindValue(b, snapshotId, snapshotKind)
        "literal", "env_fact" -> b.value?.trim()?.ifEmpty { null } ?: b.default
        else -> b.value?.trim()?.ifEmpty { null } ?: b.default
    }
}

internal fun resolveSnapshotKindValue(
    b: ClientConfigBindingFacts,
    snapshotId: String,
    snapshotKind: String?,
): String?
{
    val map = b.map
    if (map.isNotEmpty())
    {
        snapshotKind?.let { kind ->
            map[kind]?.trim()?.ifEmpty { null }?.let { return it }
        }
        if (snapshotId.isNotEmpty())
        {
            map[snapshotId]?.trim()?.ifEmpty { null }?.let { return it }
        }
    }
    return b.default
}

private fun joinRelative(base: String, relative: String?): String
{
    val root = base.trim().trimEnd('/')
    val leaf = relative?.trim()?.trim('/').orEmpty()
    if (root.isEmpty())
    {
        return if (leaf.isEmpty()) "" else "/$leaf"
    }
    if (leaf.isEmpty())
    {
        return root
    }
    return "$root/$leaf"
}

private fun parseInstallOptions(raw: String): Map<String, String>
{
    if (raw.isBlank())
    {
        return emptyMap()
    }
    return runCatching {
        val el = optionsJson.parseToJsonElement(raw)
        val obj = el as? JsonObject ?: el.jsonObject
        obj.mapNotNull { (k, v) ->
            val s = v.jsonPrimitive.contentOrNull ?: return@mapNotNull null
            k to s
        }.toMap()
    }.getOrDefault(emptyMap())
}
