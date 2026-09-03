package rpcnode.toolkit.agent.application.node

fun interface NotifyPanelNodeStarted
{
    suspend operator fun invoke(
        panelUrl: String,
        token: String,
        serverId: String,
        nodeId: String,
        pid: Long,
        clientVersion: String,
    ): Boolean
}

fun interface PushPanelNodeHeights
{
    suspend operator fun invoke(
        panelUrl: String,
        token: String,
        serverId: String,
        items: List<NodeHeightItem>,
    ): Boolean
}

data class NodeHeightItem(
    val nodeId: String,
    val height: Long,
    val clientVersion: String = "",
    /** Total bytes under the node data directory on the host; -1 = unknown. */
    val sizeOnDisk: Long = -1,
    /** Host IBD/snap progress 0..100; null = omit. */
    val syncPct: Double? = null,
    val syncing: Boolean = false,
)
