package rpcnode.toolkit.agent.infrastructure.proc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rpcnode.toolkit.agent.domain.model.BlockDevice
import rpcnode.toolkit.agent.domain.model.HostDiskInventory
import rpcnode.toolkit.agent.domain.model.MountPoint
import rpcnode.toolkit.agent.domain.model.formatSizeHuman
import rpcnode.toolkit.agent.domain.model.isPreferredDisk
import rpcnode.toolkit.agent.domain.model.isRaidMemberFsType
import rpcnode.toolkit.agent.domain.model.plannedMountForDisk
import rpcnode.toolkit.agent.domain.model.unusedFromInventory
import rpcnode.toolkit.agent.domain.model.wholeDiskForDev

/** Parses `lsblk -J` output into host disk inventory. */
object LsblkHostDiskParser
{
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(lsblkJson: String): HostDiskInventory
    {
        if (lsblkJson.isBlank())
        {
            return HostDiskInventory(emptyList(), emptyList(), emptyList())
        }
        val root = json.parseToJsonElement(lsblkJson).jsonObject
        val devices = root["blockdevices"]?.jsonArray ?: JsonArray(emptyList())
        val disks = mutableListOf<BlockDevice>()
        val mounts = mutableListOf<MountPoint>()
        val raidMemberDisks = mutableSetOf<String>()
        for (node in devices)
        {
            walkNode(node, disks, mounts, raidMemberDisks)
        }
        val dedupedDisks = disks
            .distinctBy { it.name }
            .filter { d ->
                !isRaidMemberFsType(d.fstype) && d.name !in raidMemberDisks
            }
        val filteredMounts = mounts.filter { !isRaidMemberFsType(it.fstype) }
        val unused = unusedFromInventory(dedupedDisks, filteredMounts)
        return HostDiskInventory(
            disks = dedupedDisks,
            mounts = filteredMounts.sortedBy { it.target },
            unused = unused,
        )
    }

    private fun walkNode(
        raw: JsonElement,
        disks: MutableList<BlockDevice>,
        mounts: MutableList<MountPoint>,
        raidMemberDisks: MutableSet<String>,
    )
    {
        val obj = raw.jsonObject
        val name = obj.string("name") ?: return
        val type = obj.string("type").orEmpty()
        val tran = obj.string("tran").orEmpty()
        val rota = obj.bool("rota")
        val sizeBytes = obj.long("size") ?: 0L
        val path = obj.string("path").orEmpty().ifBlank { "/dev/$name" }
        val model = obj.string("model").orEmpty()
        val mountpoint = obj.string("mountpoint").orEmpty()
        val fstype = obj.string("fstype").orEmpty()
        if (isRaidMemberFsType(fstype))
        {
            val disk = wholeDiskForDev(path.ifBlank { name })
            if (disk.isNotBlank())
            {
                raidMemberDisks += disk
            }
        }
        val fsavail = obj.long("fsavail") ?: 0L
        val fsuse = obj.double("fsuse%") ?: 0.0
        val preferred = isPreferredDisk(tran, rota)

        if (type == "disk" && !isRaidMemberFsType(fstype))
        {
            disks += BlockDevice(
                name = name,
                path = path,
                model = model,
                sizeBytes = sizeBytes,
                sizeHuman = formatSizeHuman(sizeBytes),
                tran = tran,
                rota = rota,
                type = type,
                mountpoint = mountpoint,
                fstype = fstype,
                fsavailBytes = fsavail,
                fsusedPct = fsuse,
                preferred = preferred,
                plannedMount = plannedMountForDisk(name),
            )
        }

        if (mountpoint.isNotEmpty() && mountpoint != "[SWAP]" && !isRaidMemberFsType(fstype))
        {
            val diskName = wholeDiskForDev(path.ifBlank { name })
            mounts += MountPoint(
                target = mountpoint,
                source = path,
                fstype = fstype,
                sizeBytes = sizeBytes,
                availBytes = fsavail,
                availHuman = formatSizeHuman(fsavail),
                usedPct = fsuse,
                diskName = diskName.ifBlank { name },
                diskPath = if (diskName.isNotEmpty()) "/dev/$diskName" else path,
                tran = tran,
                rota = rota,
                preferred = preferred,
            )
        }

        val children = obj["children"]?.jsonArray ?: return
        for (child in children)
        {
            walkNode(child, disks, mounts, raidMemberDisks)
        }
    }

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.double(key: String): Double? = this[key]?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.bool(key: String): Boolean =
        this[key]?.jsonPrimitive?.contentOrNull?.equals("true", ignoreCase = true) == true ||
            this[key]?.jsonPrimitive?.contentOrNull == "1"
}
