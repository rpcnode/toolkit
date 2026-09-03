package rpcnode.toolkit.nodes.application.remove

/** Panel → host agent: stop unit, optionally wipe node_dir. */
data class RemoveNodeOnHostCommand(
    val nodeId: String,
    val network: String,
    val env: String,
    val nodeDir: String?,
    val wipeData: Boolean,
)

sealed interface RemoveNodeOnHostResult
{
    data object Ok : RemoveNodeOnHostResult
    data class Failed(val error: String, val message: String) : RemoveNodeOnHostResult
}

fun interface RemoveNodeOnHost
{
    suspend fun remove(
        agentUrl: String,
        token: String,
        command: RemoveNodeOnHostCommand,
    ): RemoveNodeOnHostResult?
}
