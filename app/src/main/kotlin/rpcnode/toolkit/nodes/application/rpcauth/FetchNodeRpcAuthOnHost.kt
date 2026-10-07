package rpcnode.toolkit.nodes.application.rpcauth

data class NodeHostRpcAuth(
    val user: String,
    val password: String,
    val path: String,
)

sealed interface FetchNodeRpcAuthResult
{
    data class Ok(val auth: NodeHostRpcAuth) : FetchNodeRpcAuthResult
    data object NotApplicable : FetchNodeRpcAuthResult
    data object Empty : FetchNodeRpcAuthResult
    data object Unauthorized : FetchNodeRpcAuthResult
    data object Unreachable : FetchNodeRpcAuthResult
}

/** GET /api/v1/node/rpc-auth on the host agent. */
fun interface FetchNodeRpcAuthOnHost
{
    suspend fun rpcAuth(
        agentUrl: String,
        token: String,
        nodeId: String,
        network: String,
        nodeDir: String?,
    ): FetchNodeRpcAuthResult
}
