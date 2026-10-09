package rpcnode.toolkit.nodes.domain.model

/** Host block device from the server agent (`GET /api/v1/host/disks`). */
data class HostBlockDevice(
    val name: String,
    val path: String = "",
    val model: String = "",
    val sizeBytes: Long = 0,
    val sizeHuman: String = "",
    val tran: String = "",
    val rota: Boolean = false,
    val type: String = "",
    val mountpoint: String = "",
    val fstype: String = "",
    val fsavailBytes: Long = 0,
    val fsusedPct: Double = 0.0,
    val preferred: Boolean = false,
    val plannedMount: String = "",
)

data class HostMount(
    val target: String,
    val source: String = "",
    val fstype: String = "",
    val sizeBytes: Long = 0,
    val availBytes: Long = 0,
    val availHuman: String = "",
    val usedPct: Double = 0.0,
    val diskName: String = "",
    val diskPath: String = "",
    val tran: String = "",
    val rota: Boolean = false,
    val preferred: Boolean = false,
)

data class HostDiskCatalog(
    val disks: List<HostBlockDevice>,
    val mounts: List<HostMount>,
    val unused: List<HostBlockDevice>,
)

data class DiskRoleDef(
    val id: String,
    val label: String,
    val leaf: String = id,
    val sizeHintGiB: Double? = null,
)

data class DiskRolePlacement(
    val id: String,
    val label: String = "",
    val leaf: String = id,
    val mount: String = "",
    val dir: String = "",
    val sizeHintGiB: Double? = null,
)

/** Operator-confirmed or recommended multi-disk layout (stored in nodes.disk_layout_json). */
data class NodeDiskLayout(
    val strategy: String = "",
    val network: String = "",
    val env: String = "",
    val roles: List<DiskRolePlacement> = emptyList(),
    val ledgerMount: String = "",
    val accountsMount: String = "",
    val snapshotsMount: String = "",
    val ledgerDir: String = "",
    val accountsDir: String = "",
    val snapshotsDir: String = "",
    val stateMount: String = "",
    val indexMount: String = "",
    val stateDir: String = "",
    val indexDir: String = "",
    val notes: List<String> = emptyList(),
)
