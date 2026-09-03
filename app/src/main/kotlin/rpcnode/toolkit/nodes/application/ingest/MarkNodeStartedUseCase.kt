package rpcnode.toolkit.nodes.application.ingest

import java.time.Instant
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.networks.application.tip.NetworkTipCache
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface MarkNodeStartedResult
{
    data class Ok(val nodeId: String, val status: String) : MarkNodeStartedResult
    data object Unauthorized : MarkNodeStartedResult
    data object NotFound : MarkNodeStartedResult
}

/** Agent callback after a successful local process start — sets status to sync and records client version. */
class MarkNodeStartedUseCase(
    private val servers: ServerRepository,
    private val nodes: NodeRepository,
    private val clients: ClientVersionRepository,
    private val facts: NetworkFactsRepository,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(
        tokenRaw: String,
        serverIdRaw: String,
        nodeIdRaw: String,
        clientVersionRaw: String = "",
    ): MarkNodeStartedResult
    {
        val server = authorizeAgentServer(servers, tokenRaw, serverIdRaw)
            ?: return MarkNodeStartedResult.Unauthorized
        val nodeId = NodeId.parse(nodeIdRaw.trim()) ?: return MarkNodeStartedResult.NotFound
        val node = nodes.findById(nodeId) ?: return MarkNodeStartedResult.NotFound
        if (node.serverId != server.id)
        {
            return MarkNodeStartedResult.Unauthorized
        }
        val now = clock()
        nodes.updateStatus(nodeId, NodeStatus.SYNC, now)
        val clientVersion = clientVersionRaw.trim()
        if (clientVersion.isNotEmpty())
        {
            val compared = compareToLatest(node, clientVersion, clients, facts)
            nodes.updateClientVersion(
                id = nodeId,
                clientVersion = clientVersion,
                clientLatest = compared.latest,
                clientUpdateAvailable = compared.updateAvailable,
                updatedAt = now,
            )
        }
        return MarkNodeStartedResult.Ok(nodeId = nodeId.value, status = NodeStatus.SYNC.value)
    }
}

data class NodeHeightSample(
    val nodeId: String,
    val height: Long,
    val clientVersion: String = "",
    /** Host `du` of the node data directory in bytes; -1 = unknown / omit. */
    val sizeOnDisk: Long = -1,
    /** Host IBD/snap progress 0..100; null = omit (keep previous). */
    val syncPct: Double? = null,
    /** True while client still reports syncing (do not promote to active on tip lag alone). */
    val syncing: Boolean = false,
)

sealed interface IngestNodeHeightsResult
{
    data class Ok(val updated: Int) : IngestNodeHeightsResult
    data object Unauthorized : IngestNodeHeightsResult
}

/** Agent height push (every ~30s). Writes local height and refreshes public tip into SQLite. */
class IngestNodeHeightsUseCase(
    private val servers: ServerRepository,
    private val nodes: NodeRepository,
    private val clients: ClientVersionRepository,
    private val facts: NetworkFactsRepository,
    private val tipCache: NetworkTipCache,
    private val tipLagActive: Long = 3,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(
        tokenRaw: String,
        serverIdRaw: String,
        samples: List<NodeHeightSample>,
    ): IngestNodeHeightsResult
    {
        val server = authorizeAgentServer(servers, tokenRaw, serverIdRaw)
            ?: return IngestNodeHeightsResult.Unauthorized
        if (samples.isEmpty())
        {
            return IngestNodeHeightsResult.Ok(0)
        }
        val now = clock()
        // One tip probe per network/env for this batch (cache TTL ≈ host push interval).
        val tipByKey = mutableMapOf<String, Long?>()
        var updated = 0
        for (sample in samples)
        {
            val nodeId = NodeId.parse(sample.nodeId.trim()) ?: continue
            val node = nodes.findById(nodeId) ?: continue
            if (node.serverId != server.id)
            {
                continue
            }
            var touched = false
            if (sample.height >= 0 || sample.syncPct != null)
            {
                val tipKey = "${node.network.value}/${node.env.value}"
                val tip = tipByKey.getOrPut(tipKey) {
                    tipCache.tip(node.network, node.env)
                }
                val heightToStore = if (sample.height >= 0) sample.height else node.height
                if (
                    nodes.updateHeight(
                        id = nodeId,
                        height = heightToStore,
                        heightAt = now,
                        updatedAt = now,
                        networkHeight = tip,
                        syncPct = sample.syncPct,
                    )
                )
                {
                    touched = true
                }
                if (sample.height >= 0)
                {
                    val caughtUp = tip != null && tip > 0 && (tip - sample.height) <= tipLagActive
                    val hostDone = !sample.syncing && (sample.syncPct == null || sample.syncPct >= 99.9)
                    if (
                        node.status.value == NodeStatus.SYNC.value &&
                        caughtUp &&
                        hostDone
                    )
                    {
                        nodes.updateStatus(nodeId, NodeStatus.ACTIVE, now)
                    }
                }
            }
            if (sample.sizeOnDisk >= 0)
            {
                if (nodes.updateSizeOnDisk(nodeId, sample.sizeOnDisk, now))
                {
                    touched = true
                }
            }
            if (touched)
            {
                updated++
            }
            // Host is source of truth: always persist whatever non-blank version it reports.
            val clientVersion = sample.clientVersion.trim()
            if (clientVersion.isNotEmpty())
            {
                val compared = compareToLatest(node, clientVersion, clients, facts)
                nodes.updateClientVersion(
                    id = nodeId,
                    clientVersion = clientVersion,
                    clientLatest = compared.latest,
                    clientUpdateAvailable = compared.updateAvailable,
                    updatedAt = now,
                )
            }
        }
        return IngestNodeHeightsResult.Ok(updated)
    }
}

internal data class ClientVersionCompare(
    val latest: String,
    val updateAvailable: Boolean,
)

internal suspend fun compareToLatest(
    node: Node,
    clientVersion: String,
    clients: ClientVersionRepository,
    facts: NetworkFactsRepository,
): ClientVersionCompare
{
    val program = facts.factsFor(node.network)?.clientConfig?.program?.trim().orEmpty()
    val pin = if (program.isNotEmpty())
    {
        clients.find(node.network, node.env, program)
    }
    else
    {
        clients.list().firstOrNull { it.network == node.network && it.env == node.env && it.currentVersion.isNotBlank() }
    }
    val latest = pin?.latestVersion?.trim()?.ifEmpty { null }
        ?: pin?.currentVersion?.trim().orEmpty()
    // Prefer pin.latest; fall back to current when latest was never probed.
    val updateAvailable = latest.isNotEmpty() &&
        clientVersion.isNotEmpty() &&
        !looseSameVersion(clientVersion, latest)
    return ClientVersionCompare(latest = latest, updateAvailable = updateAvailable)
}

/** Loose match ignoring a leading `v`/`V` (same idea as clients.sameVersion). */
internal fun looseSameVersion(a: String, b: String): Boolean
{
    fun norm(s: String) = s.trim().removePrefix("v").removePrefix("V").trim()
    val x = norm(a)
    val y = norm(b)
    return x.isNotEmpty() && x == y
}

internal suspend fun authorizeAgentServer(
    servers: ServerRepository,
    tokenRaw: String,
    serverIdRaw: String,
): Server?
{
    val token = tokenRaw.trim()
    if (token.isEmpty())
    {
        return null
    }
    val claimed = ServerId.parse(serverIdRaw.trim())
    val server = if (claimed != null)
    {
        val found = servers.find(claimed) ?: return null
        if (found.agentKey != token)
        {
            return null
        }
        found
    }
    else
    {
        servers.findByAgentKey(token) ?: return null
    }
    if (!server.isActive())
    {
        return null
    }
    return server
}
