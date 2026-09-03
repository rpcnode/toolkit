package rpcnode.toolkit.nodes.application.snapshot

import rpcnode.toolkit.networks.application.snapshot.ListSnapshotSourcesUseCase
import rpcnode.toolkit.networks.application.snapshot.SnapshotSourcesResult
import rpcnode.toolkit.networks.application.snapshot.defaultSnapshotType
import rpcnode.toolkit.networks.application.snapshot.snapshotTypeFromInstallOptions
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

data class SnapshotSourceSpeedResult(
    val id: String,
    val available: Boolean,
    val bytesPerSec: Long? = null,
    val sampleBytes: Long? = null,
    val latencyMs: Long? = null,
    val detail: String? = null,
)

sealed interface ProbeNodeSnapshotSourcesResult
{
    data class Ok(val results: List<SnapshotSourceSpeedResult>) : ProbeNodeSnapshotSourcesResult
    data object NotFound : ProbeNodeSnapshotSourcesResult
    data object NoSnapshot : ProbeNodeSnapshotSourcesResult
    data object ServerNotFound : ProbeNodeSnapshotSourcesResult
    data object AgentUnreachable : ProbeNodeSnapshotSourcesResult
}

/**
 * Resolves snapshot sources for a node, then asks the host agent to sample-download each URL
 * (discarded bytes) and report throughput.
 */
class ProbeNodeSnapshotSourcesUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val facts: NetworkFactsRepository,
    private val listSources: ListSnapshotSourcesUseCase,
    private val probeOnHost: ProbeSnapshotOnHost,
)
{
    suspend operator fun invoke(
        idRaw: String,
        snapshotType: String? = null,
        sourceIds: List<String>? = null,
    ): ProbeNodeSnapshotSourcesResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return ProbeNodeSnapshotSourcesResult.NotFound
        val node = nodes.findById(id) ?: return ProbeNodeSnapshotSourcesResult.NotFound
        if (!nodeNeedsSnapshot(node, facts))
        {
            return ProbeNodeSnapshotSourcesResult.NoSnapshot
        }
        val typeId = snapshotType?.trim()?.lowercase().orEmpty().ifBlank {
            snapshotTypeFromInstallOptions(node.installOptionsJson)
                ?: defaultSnapshotType(facts, node.network, node.env.value)
                ?: ""
        }
        val listed = when (val result = listSources(node.network.value, node.env.value, typeId = typeId))
        {
            SnapshotSourcesResult.UnknownEnv,
            SnapshotSourcesResult.UnknownNetwork,
            -> return ProbeNodeSnapshotSourcesResult.Ok(emptyList())
            is SnapshotSourcesResult.Resolved -> result
        }
        val wanted = sourceIds?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
        val samples = listed.sources
            .filter { source ->
                source.url?.isNotBlank() == true &&
                    (wanted == null || wanted.contains(source.id.lowercase()))
            }
            .map { SnapshotHostSpeedSample(id = it.id, url = it.url!!) }
        if (samples.isEmpty())
        {
            return ProbeNodeSnapshotSourcesResult.Ok(
                listed.sources.map {
                    SnapshotSourceSpeedResult(
                        id = it.id,
                        available = false,
                        detail = it.detail ?: "Source URL is not available",
                    )
                },
            )
        }
        val server = servers.find(node.serverId) ?: return ProbeNodeSnapshotSourcesResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return ProbeNodeSnapshotSourcesResult.AgentUnreachable
        }
        val probed = probeOnHost.probe(agentUrl, agentKey, samples)
            ?: return ProbeNodeSnapshotSourcesResult.AgentUnreachable
        val probedById = probed.associateBy { it.id.lowercase() }
        val merged = listed.sources.map { source ->
            val hit = probedById[source.id.lowercase()]
            if (hit != null)
            {
                SnapshotSourceSpeedResult(
                    id = hit.id,
                    available = hit.available,
                    bytesPerSec = hit.bytesPerSec,
                    sampleBytes = hit.sampleBytes,
                    latencyMs = hit.latencyMs,
                    detail = hit.detail,
                )
            }
            else
            {
                SnapshotSourceSpeedResult(
                    id = source.id,
                    available = false,
                    detail = source.detail ?: "Not probed",
                )
            }
        }
        return ProbeNodeSnapshotSourcesResult.Ok(merged)
    }
}
