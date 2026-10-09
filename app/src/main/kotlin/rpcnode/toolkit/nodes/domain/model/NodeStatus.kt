package rpcnode.toolkit.nodes.domain.model

@JvmInline
value class NodeStatus private constructor(val value: String)
{
    companion object
    {
        val AWAITING_PORTS = NodeStatus("awaiting_ports")
        /** Node process is up and catching up with the network. */
        val SYNC = NodeStatus("sync")
        /** Node has caught up with the public tip (or is within the tip lag threshold). */
        val ACTIVE = NodeStatus("active")
        /** Systemd unit was stopped from Sync (or is not running). */
        val STOPPED = NodeStatus("stopped")

        fun parse(raw: String): NodeStatus
        {
            val n = raw.trim()
            if (n.isEmpty())
            {
                return AWAITING_PORTS
            }
            return NodeStatus(n)
        }

        fun reportsHeight(status: NodeStatus): Boolean =
            status.value == SYNC.value || status.value == ACTIVE.value

        fun unitRunning(status: NodeStatus): Boolean =
            status.value == SYNC.value || status.value == ACTIVE.value
    }
}
