package rpcnode.toolkit.nodes.application.logs

/** One tail of a host node process log from the agent. */
data class NodeHostLogs(
    val path: String = "",
    val lines: List<String> = emptyList(),
    val truncated: Boolean = false,
)

sealed interface FetchNodeLogsResult
{
    data class Ok(val logs: NodeHostLogs) : FetchNodeLogsResult
    data object Empty : FetchNodeLogsResult
    data object Unauthorized : FetchNodeLogsResult
    data object Unreachable : FetchNodeLogsResult
}

fun interface FetchNodeLogsOnHost
{
    /** GET /api/v1/node/logs on the host agent. */
    suspend fun logs(
        agentUrl: String,
        token: String,
        nodeId: String,
        lines: Int,
        nodeDir: String?,
        logFile: String?,
    ): FetchNodeLogsResult
}
