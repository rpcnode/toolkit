package rpcnode.toolkit.nodes.domain.model

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.servers.domain.model.ServerId

data class Node(
    val id: NodeId,
    val serverId: ServerId,
    val name: String,
    val network: NetworkId,
    val env: EnvId,
    val publicPort: Int = 0,
    val agentPort: Int = 0,
    val nodeHttpPort: Int = 0,
    val p2pPort: Int = 0,
    val status: NodeStatus = NodeStatus.AWAITING_PORTS,
    val diskLayoutJson: String = "",
    val installOptionsJson: String = "",
    /** Global proxy list id this node's outbound traffic goes through; blank = direct. */
    val proxyId: String = "",
    val height: Long = 0,
    val heightAt: String = "",
    /** Last known public tip for this network/env (panel probe), for sync progress. */
    val networkHeight: Long = 0,
    /**
     * Host-reported IBD / snap sync progress 0..100; -1 = unknown.
     * Used when height≈tip while state download is still running (geth snap).
     */
    val syncPct: Double = -1.0,
    /** Total bytes under the node data directory on the host (`du`); -1 = unknown. */
    val sizeOnDisk: Long = -1,
    /** Runtime client version reported by the host agent hooks. */
    val clientVersion: String = "",
    /**
     * Filled at list/get time from Clients pin (`client_versions.latest`), not a
     * durable source of truth — DB may lag; API always overlays the pin.
     */
    val clientLatest: String = "",
    /** True when [clientVersion] ≠ pin latest (computed with [clientLatest]). */
    val clientUpdateAvailable: Boolean = false,
    /** Last live "Test" result: `pass` | `fail` | empty = never run. */
    val liveTestStatus: String = "",
    val liveTestAt: String = "",
    val liveTestError: String = "",
    /** First time the node left `awaiting_ports` (the install began); blank = not yet. Set by [NodeLifecycleStamps]. */
    val installStartedAt: String = "",
    /** First time the node became `active` (caught up with the network); blank = not yet. */
    val syncedAt: String = "",
    val createdAt: String,
    val updatedAt: String,
)
{
    companion object
    {
        fun defaultName(label: String, env: EnvId): String
        {
            val n = label.trim().ifEmpty { "node" }
            return "$n ${env.value}"
        }
    }
}
