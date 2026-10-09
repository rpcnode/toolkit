package rpcnode.toolkit.nodes.application.snapshot

import java.nio.file.Path
import rpcnode.toolkit.networks.application.snapshot.defaultSnapshotType
import rpcnode.toolkit.networks.application.snapshot.snapshotTypeFromInstallOptions
import rpcnode.toolkit.networks.application.snapshot.snapshotTypesFor
import rpcnode.toolkit.networks.domain.model.SnapshotTypeFacts
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.application.disks.GetNodeDiskLayoutUseCase
import rpcnode.toolkit.nodes.application.disks.NodeDiskLayoutResult
import rpcnode.toolkit.nodes.domain.model.Node

class ResolveSnapshotDestDirUseCase(
    private val getNodeDiskLayout: GetNodeDiskLayoutUseCase,
    private val facts: NetworkFactsRepository,
)
{
    suspend operator fun invoke(node: Node): String?
    {
        val base = snapshotDestDir(node.diskLayoutJson)
            ?: when (val layout = getNodeDiskLayout(node.id.value))
            {
                is NodeDiskLayoutResult.Ok -> layout.diskLayout?.let { snapshotDestDir(it) }
                NodeDiskLayoutResult.NotFound -> null
            }
            ?: return null
        val typeId = snapshotTypeFromInstallOptions(node.installOptionsJson)
            ?: defaultSnapshotType(facts, node.network, node.env.value)
        val types = snapshotTypesFor(facts, node.network, node.env.value)
        return applySnapshotDestLeaf(base, typeId, types)
    }
}

/**
 * Disk layout role leaf is usually `fullnode`. Lite (and explicit [SnapshotTypeFacts.destLeaf])
 * replace the last path segment so extract lands in e.g. `…/nile/litefullnode`.
 */
internal fun applySnapshotDestLeaf(
    baseDir: String,
    typeId: String?,
    types: List<SnapshotTypeFacts>,
): String
{
    val base = baseDir.trim()
    if (base.isEmpty())
    {
        return base
    }
    val id = typeId?.trim()?.lowercase().orEmpty()
    val type = types.firstOrNull { it.id == id }
    val leaf = type?.destLeaf?.trim()?.trimStart('/')?.takeIf { it.isNotBlank() }
        ?: when ((type?.kind ?: id).lowercase())
        {
            "lite" -> "litefullnode"
            else -> return base
        }
    return replacePathLeaf(base, leaf)
}

internal fun replacePathLeaf(path: String, newLeaf: String): String
{
    val trimmed = path.trimEnd('/')
    val parent = Path.of(trimmed).parent ?: return trimmed
    return parent.resolve(newLeaf).toString()
}
