package rpcnode.toolkit.nodes.application.config

data class ClientSyncOnHostCommand(
    val network: String,
    val env: String,
    val nodeDir: String,
    val configAssignments: Map<String, String>,
    val configFormat: String,
    val configFile: String?,
    val configIniSection: String? = null,
    val configOmitIniKeys: Set<String> = emptySet(),
)

sealed interface ClientSyncOnHostResult
{
    data class Ok(val nodeDir: String, val files: List<String>, val configPath: String?) : ClientSyncOnHostResult
    data class Failed(val error: String, val message: String) : ClientSyncOnHostResult
}

fun interface SyncClientOnHost
{
    /** POST /api/v1/client/sync — null = unreachable. */
    suspend fun sync(agentUrl: String, token: String, command: ClientSyncOnHostCommand): ClientSyncOnHostResult?
}
