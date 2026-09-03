package rpcnode.toolkit.nodes.application.snapshot

import rpcnode.toolkit.networks.application.snapshot.ListSnapshotSourcesUseCase
import rpcnode.toolkit.networks.application.snapshot.SnapshotSourceOption
import rpcnode.toolkit.networks.application.snapshot.SnapshotSourcesResult
import rpcnode.toolkit.networks.application.snapshot.defaultSnapshotType
import rpcnode.toolkit.networks.application.snapshot.snapshotTypeFromInstallOptions
import rpcnode.toolkit.networks.application.snapshot.snapshotTypesFor
import rpcnode.toolkit.networks.domain.model.SnapshotTypeFacts
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository

sealed interface NodeSnapshotPlanResult
{
    data class Ok(
        val url: String?,
        val officialUrl: String?,
        val version: String?,
        val source: String?,
        val streamUnpack: Boolean?,
        val sizeBytes: Long?,
        val destDir: String?,
        val status: String,
        val typeId: String?,
        val snapshotTypes: List<SnapshotTypeFacts>,
        val sources: List<SnapshotSourceOption>,
        val defaultSourceId: String?,
        /** Chain process downloads the snapshot (no toolkit aria2 URL). */
        val viaNode: Boolean = false,
    ) : NodeSnapshotPlanResult

    data object NotFound : NodeSnapshotPlanResult
    data object NoSnapshot : NodeSnapshotPlanResult
    data object MissingDest : NodeSnapshotPlanResult
}

class GetNodeSnapshotPlanUseCase(
    private val nodes: NodeRepository,
    private val facts: NetworkFactsRepository,
    private val listSources: ListSnapshotSourcesUseCase,
    private val resolveDestDir: ResolveSnapshotDestDirUseCase,
)
{
    suspend operator fun invoke(idRaw: String): NodeSnapshotPlanResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return NodeSnapshotPlanResult.NotFound
        val node = nodes.findById(id) ?: return NodeSnapshotPlanResult.NotFound
        if (!nodeNeedsSnapshot(node, facts))
        {
            return NodeSnapshotPlanResult.NoSnapshot
        }
        val dest = resolveDestDir(node)
        if (dest.isNullOrBlank())
        {
            return NodeSnapshotPlanResult.MissingDest
        }
        val types = snapshotTypesFor(facts, node.network, node.env.value)
        val typeId = snapshotTypeFromInstallOptions(node.installOptionsJson)
            ?: defaultSnapshotType(facts, node.network, node.env.value)
            ?: ""
        val viaNode = nodeSnapshotViaNode(node, facts)
        if (viaNode)
        {
            return NodeSnapshotPlanResult.Ok(
                url = null,
                officialUrl = null,
                version = null,
                source = "via_node",
                streamUnpack = null,
                sizeBytes = null,
                destDir = dest,
                status = node.status.value,
                typeId = typeId.ifBlank { null },
                snapshotTypes = types,
                sources = emptyList(),
                defaultSourceId = null,
                viaNode = true,
            )
        }
        when (val listed = listSources(node.network.value, node.env.value, typeId = typeId))
        {
            SnapshotSourcesResult.UnknownEnv,
            SnapshotSourcesResult.UnknownNetwork,
            ->
                return NodeSnapshotPlanResult.Ok(
                    url = null,
                    officialUrl = null,
                    version = null,
                    source = null,
                    streamUnpack = null,
                    sizeBytes = null,
                    destDir = dest,
                    status = node.status.value,
                    typeId = typeId.ifBlank { null },
                    snapshotTypes = types,
                    sources = emptyList(),
                    defaultSourceId = null,
                    viaNode = false,
                )
            is SnapshotSourcesResult.Resolved ->
            {
                val default = listed.defaultSourceId
                val selected = listed.sources.firstOrNull { it.id == default && it.available }
                    ?: listed.sources.firstOrNull { it.available }
                return NodeSnapshotPlanResult.Ok(
                    url = selected?.url,
                    officialUrl = listed.officialUrl,
                    version = selected?.version ?: listed.officialVersion,
                    source = selected?.id,
                    streamUnpack = selected?.streamUnpack,
                    sizeBytes = selected?.sizeBytes,
                    destDir = dest,
                    status = node.status.value,
                    typeId = listed.typeId.ifBlank { typeId }.ifBlank { null },
                    snapshotTypes = types,
                    sources = listed.sources,
                    defaultSourceId = default,
                    viaNode = false,
                )
            }
        }
    }
}
