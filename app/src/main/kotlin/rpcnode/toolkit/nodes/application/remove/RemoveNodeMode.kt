package rpcnode.toolkit.nodes.application.remove

/** How much the removal touches beyond the panel's own row. */
enum class RemoveNodeMode(val value: String)
{
    /** Stop the node, remove the systemd unit, wipe chain data under node_dir. */
    WIPE("wipe"),

    /** Stop the node and drop the systemd unit; keep chain data on disk. */
    AGENTS("agents"),

    /** Drop the panel row only. The node keeps running on the host. */
    PANEL("panel"),

    ;

    companion object
    {
        fun parse(raw: String): RemoveNodeMode?
        {
            val v = raw.trim().lowercase()
            return entries.firstOrNull { it.value == v }
        }
    }
}
