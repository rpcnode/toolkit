package rpcnode.toolkit.nodes.application.start

data class StartNodeOnHostCommand(
    val nodeId: String,
    val network: String,
    val env: String,
    val nodeDir: String,
    val configFile: String?,
    val httpPort: Int,
    val program: String = "",
    val clientVersion: String = "",
    val launch: NodeLaunchSpec,
    val height: NodeHeightSpec,
)

sealed interface StartNodeOnHostResult
{
    data class Ok(val pid: Long, val alreadyRunning: Boolean = false) : StartNodeOnHostResult
    data class Failed(val error: String, val message: String) : StartNodeOnHostResult
    data class Pending(val error: String, val message: String) : StartNodeOnHostResult
}

fun interface StartNodeOnHost
{
    /** POST /api/v1/node/start — null = unreachable. */
    suspend fun start(agentUrl: String, token: String, command: StartNodeOnHostCommand): StartNodeOnHostResult?
}
