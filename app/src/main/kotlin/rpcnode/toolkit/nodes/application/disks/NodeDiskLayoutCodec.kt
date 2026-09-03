package rpcnode.toolkit.nodes.application.disks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import rpcnode.toolkit.nodes.domain.model.DiskRoleDef
import rpcnode.toolkit.nodes.domain.model.DiskRolePlacement
import rpcnode.toolkit.nodes.domain.model.NodeDiskLayout

private val layoutJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

@Serializable
private data class DiskRolePlacementPayload(
    val id: String = "",
    val label: String = "",
    val leaf: String = "",
    val mount: String = "",
    val dir: String = "",
    @SerialName("size_hint_gib") val sizeHintGiB: Double? = null,
)

@Serializable
private data class NodeDiskLayoutPayload(
    val strategy: String = "",
    val network: String = "",
    val env: String = "",
    val roles: List<DiskRolePlacementPayload>? = null,
    @SerialName("roles_map") val rolesMap: Map<String, RoleDirMountPayload>? = null,
    val notes: List<String> = emptyList(),
    @SerialName("ledger_mount") val ledgerMount: String = "",
    @SerialName("accounts_mount") val accountsMount: String = "",
    @SerialName("snapshots_mount") val snapshotsMount: String = "",
    @SerialName("ledger_dir") val ledgerDir: String = "",
    @SerialName("accounts_dir") val accountsDir: String = "",
    @SerialName("snapshots_dir") val snapshotsDir: String = "",
    @SerialName("state_mount") val stateMount: String = "",
    @SerialName("index_mount") val indexMount: String = "",
    @SerialName("state_dir") val stateDir: String = "",
    @SerialName("index_dir") val indexDir: String = "",
)

@Serializable
private data class RoleDirMountPayload(
    val dir: String = "",
    val mount: String = "",
)

fun decodeNodeDiskLayout(raw: String): NodeDiskLayout?
{
    if (raw.isBlank())
    {
        return null
    }
    return runCatching {
        val root = layoutJson.parseToJsonElement(raw).jsonObject
        val normalized = normalizeDiskLayoutJson(root)
        layoutJson.decodeFromJsonElement(NodeDiskLayoutPayload.serializer(), normalized).toDomain()
    }.getOrNull()
}

/** Admin saves `roles` as a map (Go provision shape); codec expects a list or `roles_map`. */
private fun normalizeDiskLayoutJson(root: JsonObject): JsonObject
{
    val roles = root["roles"] ?: return root
    if (roles !is JsonObject)
    {
        return root
    }
    if (root.containsKey("roles_map"))
    {
        return root
    }
    return buildJsonObject {
        for ((key, value) in root)
        {
            when (key)
            {
                "roles" -> put("roles_map", value)
                else -> put(key, value)
            }
        }
    }
}

private fun NodeDiskLayoutPayload.toDomain(): NodeDiskLayout
{
    val fromRoles = roles.orEmpty().map { it.toDomain() }.filter { it.id.isNotBlank() }
    val layout = NodeDiskLayout(
        strategy = strategy,
        network = network,
        env = env,
        roles = fromRoles,
        ledgerMount = ledgerMount,
        accountsMount = accountsMount,
        snapshotsMount = snapshotsMount,
        ledgerDir = ledgerDir,
        accountsDir = accountsDir,
        snapshotsDir = snapshotsDir,
        stateMount = stateMount,
        indexMount = indexMount,
        stateDir = stateDir,
        indexDir = indexDir,
        notes = notes,
    )
    return when
    {
        fromRoles.isNotEmpty() -> layout
        !rolesMap.isNullOrEmpty() -> layout.copy(roles = rolesMap.map { (id, v) ->
            DiskRolePlacement(id = id, mount = v.mount, dir = v.dir)
        })
        else -> layout.copy(roles = layout.placementsFromCompatFields())
    }
}

private fun DiskRolePlacementPayload.toDomain() = DiskRolePlacement(
    id = id,
    label = label,
    leaf = leaf.ifBlank { id },
    mount = mount,
    dir = dir,
    sizeHintGiB = sizeHintGiB,
)

fun NodeDiskLayout.placementsFromCompatFields(): List<DiskRolePlacement>
{
    if (roles.isNotEmpty())
    {
        return roles
    }
    val legacy = mutableListOf<DiskRolePlacement>()
    fun push(id: String, dir: String, mount: String)
    {
        if (dir.isNotBlank() || mount.isNotBlank())
        {
            legacy += DiskRolePlacement(id = id, dir = dir, mount = mount)
        }
    }
    push("ledger", ledgerDir, ledgerMount)
    push("accounts", accountsDir, accountsMount)
    push("snapshots", snapshotsDir, snapshotsMount)
    push("state", stateDir, stateMount)
    push("index", indexDir, indexMount)
    return legacy
}

private val LEGACY_ROLE_ORDER = listOf("primary", "secondary", "tertiary", "quaternary")

/** Merge saved layout with network catalog — labels, size hints, and dir paths per role. */
fun enrichDiskLayout(
    layout: NodeDiskLayout?,
    catalog: List<DiskRoleDef>,
    network: String,
    env: String,
): NodeDiskLayout?
{
    if (catalog.isEmpty())
    {
        return layout
    }
    val saved = layout?.placementsFromCompatFields().orEmpty()
    if (saved.isEmpty())
    {
        return emptyDiskLayout(catalog, network, env, layout?.strategy.orEmpty())
    }

    val catalogIds = catalog.map { it.id }.toSet()
    val remapped = if (saved.all { it.id in catalogIds })
    {
        saved
    }
    else
    {
        remapLegacyRoles(saved, catalog)
    }

    val byId = remapped.associateBy { it.id }
    val roles = catalog.map { def ->
        val p = byId[def.id]
        val mount = p?.mount.orEmpty()
        var dir = p?.dir.orEmpty()
        if (dir.isBlank() && mount.isNotBlank())
        {
            dir = pathOnDataMount(mount, network, env, def.leaf)
        }
        DiskRolePlacement(
            id = def.id,
            label = def.label,
            leaf = def.leaf,
            mount = mount,
            dir = dir,
            sizeHintGiB = def.sizeHintGiB,
        )
    }
    return NodeDiskLayout(
        strategy = layout?.strategy.orEmpty().ifBlank { strategyFor(roles) },
        network = layout?.network.orEmpty().ifBlank { network },
        env = layout?.env.orEmpty().ifBlank { env },
        roles = roles,
        notes = layout?.notes.orEmpty(),
    ).withCompatFields()
}

fun emptyDiskLayout(
    catalog: List<DiskRoleDef>,
    network: String,
    env: String,
    strategy: String = "",
): NodeDiskLayout
{
    val roles = catalog.map { def ->
        DiskRolePlacement(
            id = def.id,
            label = def.label,
            leaf = def.leaf,
            sizeHintGiB = def.sizeHintGiB,
        )
    }
    return NodeDiskLayout(
        strategy = strategy,
        network = network,
        env = env,
        roles = roles,
    )
}

private fun remapLegacyRoles(
    saved: List<DiskRolePlacement>,
    catalog: List<DiskRoleDef>,
): List<DiskRolePlacement>
{
    val ordered = mutableListOf<DiskRolePlacement>()
    val seen = mutableSetOf<String>()
    fun add(id: String)
    {
        if (id in seen)
        {
            return
        }
        val p = saved.firstOrNull { it.id == id } ?: return
        if (p.mount.isBlank() && p.dir.isBlank())
        {
            return
        }
        seen += id
        ordered += p
    }
    for (id in LEGACY_ROLE_ORDER)
    {
        add(id)
    }
    for (p in saved.sortedBy { it.id })
    {
        if (p.id !in seen)
        {
            add(p.id)
        }
    }
    return catalog.mapIndexed { index, def ->
        ordered.getOrNull(index)?.copy(id = def.id) ?: DiskRolePlacement(id = def.id)
    }
}

private fun strategyFor(roles: List<DiskRolePlacement>): String
{
    val distinct = roles.map { it.mount }.filter { it.isNotBlank() }.toSet()
    return when
    {
        distinct.size >= 3 -> "jbod_3"
        distinct.size == 2 -> "jbod_2"
        distinct.isNotEmpty() -> "single"
        else -> ""
    }
}

fun encodeNodeDiskLayout(layout: NodeDiskLayout): String =
    layoutJson.encodeToString(NodeDiskLayoutPayload.serializer(), layout.toPayload())

private fun NodeDiskLayout.toPayload() = NodeDiskLayoutPayload(
    strategy = strategy,
    network = network,
    env = env,
    roles = roles.map {
        DiskRolePlacementPayload(
            id = it.id,
            label = it.label,
            leaf = it.leaf,
            mount = it.mount,
            dir = it.dir,
            sizeHintGiB = it.sizeHintGiB,
        )
    },
    rolesMap = roles.associate { it.id to RoleDirMountPayload(dir = it.dir, mount = it.mount) },
    notes = notes,
    ledgerMount = ledgerMount,
    accountsMount = accountsMount,
    snapshotsMount = snapshotsMount,
    ledgerDir = ledgerDir,
    accountsDir = accountsDir,
    snapshotsDir = snapshotsDir,
    stateMount = stateMount,
    indexMount = indexMount,
    stateDir = stateDir,
    indexDir = indexDir,
)
