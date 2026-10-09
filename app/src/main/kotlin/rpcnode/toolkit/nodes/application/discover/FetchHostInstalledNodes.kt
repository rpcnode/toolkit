package rpcnode.toolkit.nodes.application.discover

data class HostInstalledNode(
    val network: String,
    val env: String,
    val status: String = "present",
    val source: String = "",
    val publicPort: Int = 0,
    val agentPort: Int = 0,
    val nodeHttpPort: Int = 0,
    val p2pPort: Int = 0,
)

sealed interface FetchHostInstalledNodesResult
{
    data class Ok(val items: List<HostInstalledNode>) : FetchHostInstalledNodesResult
    data object Unauthorized : FetchHostInstalledNodesResult
    data class Unreachable(val detail: String = "") : FetchHostInstalledNodesResult
}

/** GET /api/v1/nodes on the host agent. */
fun interface FetchHostInstalledNodes
{
    suspend fun list(agentUrl: String, token: String): FetchHostInstalledNodesResult
}
