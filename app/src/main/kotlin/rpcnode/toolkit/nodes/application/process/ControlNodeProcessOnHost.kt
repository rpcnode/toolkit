package rpcnode.toolkit.nodes.application.process

/** Host agent control of an already-installed node systemd unit. */
data class NodeProcessControlResult(
    val ok: Boolean,
    val pid: Long = 0,
    val action: String = "",
    val error: String = "",
    val message: String = "",
)

fun interface ControlNodeProcessOnHost
{
    suspend fun control(
        agentUrl: String,
        token: String,
        nodeId: String,
        network: String,
        env: String,
        action: String,
    ): NodeProcessControlResult?
}
