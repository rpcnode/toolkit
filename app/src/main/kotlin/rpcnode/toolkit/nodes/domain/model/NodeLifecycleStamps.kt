package rpcnode.toolkit.nodes.domain.model

/**
 * The two lifecycle dates of the node page ("Install", "Synced"). Both are written once and then kept: a node that is stopped,
 * restarted or goes back to syncing keeps the date of its first install start and of its first catch-up.
 */
object NodeLifecycleStamps
{
    /** [current] once set; else [now] from the first status that is not `awaiting_ports` (ports confirmed, install under way). */
    fun installStarted(current: String, status: NodeStatus, now: String): String =
        when
        {
            current.isNotBlank() -> current
            status == NodeStatus.AWAITING_PORTS -> ""
            else -> now
        }

    /** [current] once set; else [now] the first time the node is `active`. */
    fun synced(current: String, status: NodeStatus, now: String): String =
        when
        {
            current.isNotBlank() -> current
            status == NodeStatus.ACTIVE -> now
            else -> ""
        }
}
