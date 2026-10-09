package rpcnode.toolkit.agent.domain.model

data class BlockDevice(
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

data class MountPoint(
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

data class HostDiskInventory(
    val disks: List<BlockDevice>,
    val mounts: List<MountPoint>,
    val unused: List<BlockDevice>,
)
