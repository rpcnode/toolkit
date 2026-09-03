package rpcnode.toolkit.nodes.application.height

import java.time.Instant
import rpcnode.toolkit.networks.application.tip.NetworkTipCache
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository

data class NodeHeightView(
    val nodeId: String,
    val status: String,
    val height: Long,
    val heightAt: String,
    val networkHeight: Long?,
    val behind: Long?,
    /** Host IBD/snap progress 0..100; null when unknown. */
    val syncPct: Double? = null,
)

sealed interface GetNodeHeightResult
{
    data class Ok(val view: NodeHeightView) : GetNodeHeightResult
    data object NotFound : GetNodeHeightResult
}

/**
 * Node height from SQLite (agent push) + public network tip (cached probe).
 * Always returns the stored height so /nodes and detail can show it for any status.
 * When the node is syncing and within [tipLagActive] of the tip, status → active.
 */
class GetNodeHeightUseCase(
    private val nodes: NodeRepository,
    private val tipCache: NetworkTipCache,
    private val tipLagActive: Long = 3,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(idRaw: String): GetNodeHeightResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return GetNodeHeightResult.NotFound
        val node = nodes.findById(id) ?: return GetNodeHeightResult.NotFound

        val tip = tipCache.tip(node.network, node.env)
            ?: node.networkHeight.takeIf { it > 0 }

        var status = node.status
        val behind = if (tip != null && tip > 0 && node.height >= 0)
        {
            (tip - node.height).coerceAtLeast(0)
        }
        else
        {
            null
        }
        if (tip != null && tip > 0 && tip != node.networkHeight)
        {
            nodes.updateHeight(
                id = id,
                height = node.height,
                heightAt = node.heightAt.ifBlank { clock() },
                updatedAt = clock(),
                networkHeight = tip,
            )
        }
        if (
            status.value == NodeStatus.SYNC.value &&
            behind != null &&
            behind <= tipLagActive &&
            (node.syncPct < 0 || node.syncPct >= 99.9)
        )
        {
            nodes.updateStatus(id, NodeStatus.ACTIVE, clock())
            status = NodeStatus.ACTIVE
        }

        return GetNodeHeightResult.Ok(
            NodeHeightView(
                nodeId = node.id.value,
                status = status.value,
                height = node.height,
                heightAt = node.heightAt,
                networkHeight = tip,
                behind = behind,
                syncPct = node.syncPct.takeIf { it >= 0 },
            ),
        )
    }
}
