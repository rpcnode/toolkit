package rpcnode.toolkit.nodes.application.snapshot

import java.time.Instant
import rpcnode.toolkit.chains.bsc.infrastructure.http.BscSnapshotResolver
import rpcnode.toolkit.networks.application.snapshot.PreferCdnSnapshotResult
import rpcnode.toolkit.networks.application.snapshot.PreferCdnSnapshotUseCase
import rpcnode.toolkit.networks.application.snapshot.defaultSnapshotType
import rpcnode.toolkit.networks.application.snapshot.snapshotTypeFromInstallOptions
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.options.SaveNodeInstallOptionsResult
import rpcnode.toolkit.nodes.application.options.SaveNodeInstallOptionsUseCase
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface StartNodeSnapshotResult
{
    data class Ok(val typeId: String, val url: String, val destDir: String) : StartNodeSnapshotResult
    data object NotFound : StartNodeSnapshotResult
    data object NoSnapshot : StartNodeSnapshotResult
    data object MissingUrl : StartNodeSnapshotResult
    data object MissingDest : StartNodeSnapshotResult
    data object InvalidType : StartNodeSnapshotResult
    data object ServerNotFound : StartNodeSnapshotResult
    data object AgentUnreachable : StartNodeSnapshotResult
    data class SourceUnavailable(val source: String, val detail: String) : StartNodeSnapshotResult
    data class AlreadyRunning(val typeId: String, val url: String, val destDir: String) : StartNodeSnapshotResult
}

/**
 * Persists snapshot type on the node (when provided), re-resolves the live archive URL
 * from the chain/CDN catalog, then starts the host agent download into dest_dir.
 */
class StartNodeSnapshotUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val facts: NetworkFactsRepository,
    private val saveInstallOptions: SaveNodeInstallOptionsUseCase,
    private val preferSnapshot: PreferCdnSnapshotUseCase,
    private val resolveDestDir: ResolveSnapshotDestDirUseCase,
    private val startOnHost: StartSnapshotOnHost,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(
        idRaw: String,
        snapshotType: String? = null,
        source: String? = null,
    ): StartNodeSnapshotResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return StartNodeSnapshotResult.NotFound
        var node = nodes.findById(id) ?: return StartNodeSnapshotResult.NotFound
        if (!nodeNeedsSnapshot(node, facts))
        {
            return StartNodeSnapshotResult.NoSnapshot
        }

        val requested = snapshotType?.trim()?.lowercase().orEmpty()
        val typeToPersist = requested.ifBlank {
            snapshotTypeFromInstallOptions(node.installOptionsJson)
                ?: defaultSnapshotType(facts, node.network, node.env.value)
                ?: ""
        }
        if (typeToPersist.isNotBlank())
        {
            when (saveInstallOptions(id.value, snapshotType = typeToPersist))
            {
                is SaveNodeInstallOptionsResult.Saved ->
                    node = nodes.findById(id) ?: return StartNodeSnapshotResult.NotFound
                SaveNodeInstallOptionsResult.NotFound ->
                    return StartNodeSnapshotResult.NotFound
                SaveNodeInstallOptionsResult.InvalidType ->
                    return StartNodeSnapshotResult.InvalidType
            }
        }

        val typeId = snapshotTypeFromInstallOptions(node.installOptionsJson)
            ?: defaultSnapshotType(facts, node.network, node.env.value)
            ?: ""
        if (typeId.isBlank())
        {
            return StartNodeSnapshotResult.MissingUrl
        }

        val dest = resolveDestDir(node)
        if (dest.isNullOrBlank())
        {
            return StartNodeSnapshotResult.MissingDest
        }

        val resolved = when (
            val snap = preferSnapshot(
                node.network.value,
                node.env.value,
                source = source,
                typeId = typeId,
            )
        )
        {
            PreferCdnSnapshotResult.UnknownEnv,
            PreferCdnSnapshotResult.UnknownNetwork,
            -> return StartNodeSnapshotResult.MissingUrl
            is PreferCdnSnapshotResult.SourceUnavailable ->
                return StartNodeSnapshotResult.SourceUnavailable(
                    source = snap.source,
                    detail = snap.detail,
                )
            is PreferCdnSnapshotResult.Resolved -> snap
        }
        var url = resolved.url?.trim().orEmpty()
        if (url.isBlank())
        {
            return StartNodeSnapshotResult.MissingUrl
        }
        if (BscSnapshotResolver.isOfficialUrl(url))
        {
            val snapRole = snapshotsRoleDir(node.diskLayoutJson)
            url = BscSnapshotResolver.withSnapDir(url, snapRole)
        }
        val server = servers.find(node.serverId) ?: return StartNodeSnapshotResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return StartNodeSnapshotResult.AgentUnreachable
        }
        val started = startOnHost.start(
            agentUrl,
            agentKey,
            SnapshotHostStartCommand(
                jobId = node.id.value,
                url = url,
                destDir = dest,
                streamUnpack = resolved.streamUnpack == true,
                sizeBytes = resolved.sizeBytes,
            ),
        ) ?: return StartNodeSnapshotResult.AgentUnreachable
        nodes.updateStatus(id, NodeStatus.parse("snapshot_running"), clock())
        return if (started)
        {
            StartNodeSnapshotResult.Ok(typeId = typeId, url = url, destDir = dest)
        }
        else
        {
            StartNodeSnapshotResult.AlreadyRunning(typeId = typeId, url = url, destDir = dest)
        }
    }
}

private fun snapshotsRoleDir(diskLayoutJson: String): String?
{
    val layout = decodeNodeDiskLayout(diskLayoutJson) ?: return null
    return layout.roles.firstOrNull { it.id == "snapshots" && it.dir.isNotBlank() }?.dir?.trim()
        ?: layout.snapshotsDir.trim().takeIf { it.isNotBlank() }
}
