package rpcnode.toolkit.nodes.infrastructure.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import rpcnode.toolkit.nodes.domain.model.DiskRoleDef
import rpcnode.toolkit.nodes.domain.model.DiskRolePlacement
import rpcnode.toolkit.nodes.domain.model.HostBlockDevice
import rpcnode.toolkit.nodes.domain.model.HostMount
import rpcnode.toolkit.nodes.domain.model.NodeDiskLayout

private val layoutJson = Json { encodeDefaults = false; ignoreUnknownKeys = true }

@Serializable
data class HostDiskItemResponse(
    val name: String,
    val path: String = "",
    val model: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("size_human") val sizeHuman: String = "",
    val tran: String = "",
    val rota: Boolean = false,
    val type: String = "",
    val mountpoint: String = "",
    val fstype: String = "",
    @SerialName("fsavail_bytes") val fsavailBytes: Long = 0,
    @SerialName("fsused_pct") val fsusedPct: Double = 0.0,
    val preferred: Boolean = false,
    @SerialName("planned_mount") val plannedMount: String = "",
)

@Serializable
data class HostMountItemResponse(
    val target: String,
    val source: String = "",
    val fstype: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("avail_bytes") val availBytes: Long = 0,
    @SerialName("avail_human") val availHuman: String = "",
    @SerialName("used_pct") val usedPct: Double = 0.0,
    @SerialName("disk_name") val diskName: String = "",
    @SerialName("disk_path") val diskPath: String = "",
    val tran: String = "",
    val rota: Boolean = false,
    val preferred: Boolean = false,
)

@Serializable
data class DiskRoleDefResponse(
    val id: String,
    val label: String,
    val leaf: String = id,
    @SerialName("size_hint_gib") val sizeHintGiB: Double? = null,
)

@Serializable
data class DiskRolePlacementResponse(
    val id: String,
    val label: String = "",
    val description: String = "",
    val leaf: String = id,
    val mount: String = "",
    val dir: String = "",
    @SerialName("size_hint_gib") val sizeHintGiB: Double? = null,
)

@Serializable
data class NodeDiskLayoutResponse(
    val strategy: String = "",
    val network: String = "",
    val env: String = "",
    val roles: List<DiskRolePlacementResponse> = emptyList(),
    @SerialName("roles_map") val rolesMap: Map<String, RoleDirMountResponse> = emptyMap(),
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
data class RoleDirMountResponse(
    val dir: String = "",
    val mount: String = "",
)

@Serializable
data class NodeHostDisksResponse(
    val ok: Boolean = true,
    val disks: List<HostDiskItemResponse> = emptyList(),
    val mounts: List<HostMountItemResponse> = emptyList(),
    val unused: List<HostDiskItemResponse> = emptyList(),
    val insights: List<Map<String, String>> = emptyList(),
    val summary: String = "",
    val recommended: NodeDiskLayoutResponse? = null,
    @SerialName("multi_disk_roles") val multiDiskRoles: List<DiskRoleDefResponse> = emptyList(),
    @SerialName("layout_rules") val layoutRules: List<String> = emptyList(),
    val network: String = "",
    val env: String = "",
    val error: String? = null,
    val message: String? = null,
)

fun HostBlockDevice.toResponse() = HostDiskItemResponse(
    name = name,
    path = path,
    model = model,
    sizeBytes = sizeBytes,
    sizeHuman = sizeHuman,
    tran = tran,
    rota = rota,
    type = type,
    mountpoint = mountpoint,
    fstype = fstype,
    fsavailBytes = fsavailBytes,
    fsusedPct = fsusedPct,
    preferred = preferred,
    plannedMount = plannedMount,
)

fun HostMount.toResponse() = HostMountItemResponse(
    target = target,
    source = source,
    fstype = fstype,
    sizeBytes = sizeBytes,
    availBytes = availBytes,
    availHuman = availHuman,
    usedPct = usedPct,
    diskName = diskName,
    diskPath = diskPath,
    tran = tran,
    rota = rota,
    preferred = preferred,
)

fun DiskRoleDef.toResponse() = DiskRoleDefResponse(
    id = id,
    label = label,
    leaf = leaf,
    sizeHintGiB = sizeHintGiB,
)

fun NodeDiskLayout.toResponse() = NodeDiskLayoutResponse(
    strategy = strategy,
    network = network,
    env = env,
    roles = roles.map { it.toResponse() },
    rolesMap = roles.associate { it.id to RoleDirMountResponse(dir = it.dir, mount = it.mount) },
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

private fun DiskRolePlacement.toResponse() = DiskRolePlacementResponse(
    id = id,
    label = label,
    leaf = leaf,
    mount = mount,
    dir = dir,
    sizeHintGiB = sizeHintGiB,
)

fun parseDiskLayoutJson(raw: String): JsonElement?
{
    if (raw.isBlank())
    {
        return null
    }
    return runCatching { layoutJson.parseToJsonElement(raw) }.getOrNull()
}

fun encodeDiskLayoutJson(raw: String): String = raw.trim()

fun decodeDiskLayoutBody(element: JsonElement): String = layoutJson.encodeToString(element)
