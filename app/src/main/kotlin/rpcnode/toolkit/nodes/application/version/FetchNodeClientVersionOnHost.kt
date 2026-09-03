package rpcnode.toolkit.nodes.application.version

/** Chain client version read from `{nodeDir}/VERSION` on the host agent. */
data class NodeHostClientVersion(
    val clientVersion: String = "",
    val path: String = "",
)

sealed interface FetchNodeClientVersionResult
{
    data class Ok(val version: NodeHostClientVersion) : FetchNodeClientVersionResult
    data object Empty : FetchNodeClientVersionResult
    data object Unauthorized : FetchNodeClientVersionResult
    data object Unreachable : FetchNodeClientVersionResult
}

fun interface FetchNodeClientVersionOnHost
{
    /** GET /api/v1/node/client-version on the host agent. */
    suspend fun clientVersion(
        agentUrl: String,
        token: String,
        nodeId: String,
        nodeDir: String?,
        seed: String?,
    ): FetchNodeClientVersionResult
}
