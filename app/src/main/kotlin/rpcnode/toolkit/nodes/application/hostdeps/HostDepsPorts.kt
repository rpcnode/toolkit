package rpcnode.toolkit.nodes.application.hostdeps

data class NodeHostDepPlanItem(
    val id: String,
    val kind: String,
    val name: String = "",
    val javaMajor: Int = 0,
    val label: String = "",
)

data class NodeHostDepsPlan(
    val nodeId: String,
    val deps: List<NodeHostDepPlanItem>,
)

data class NodeHostDepProgressItem(
    val id: String,
    val status: String,
    val detail: String = "",
)

data class NodeHostDepsProgress(
    val jobId: String,
    val phase: String,
    val detail: String = "",
    val pct: Int = 0,
    val currentId: String = "",
    val items: List<NodeHostDepProgressItem> = emptyList(),
    val ready: Boolean = false,
    val failed: Boolean = false,
    val error: String = "",
    val logTail: List<String> = emptyList(),
)

sealed interface GetNodeHostDepsPlanResult
{
    data class Ok(val plan: NodeHostDepsPlan) : GetNodeHostDepsPlanResult
    data object NotFound : GetNodeHostDepsPlanResult
}

sealed interface StartNodeHostDepsResult
{
    data class Ok(val jobId: String, val deps: List<NodeHostDepPlanItem>) : StartNodeHostDepsResult
    data object NotFound : StartNodeHostDepsResult
    data object ServerNotFound : StartNodeHostDepsResult
    data object AgentUnreachable : StartNodeHostDepsResult
    data object InvalidAgentKey : StartNodeHostDepsResult
    data object NothingToInstall : StartNodeHostDepsResult
}

sealed interface GetNodeHostDepsProgressResult
{
    data class Ok(val progress: NodeHostDepsProgress) : GetNodeHostDepsProgressResult
    data object NotFound : GetNodeHostDepsProgressResult
    data object ServerNotFound : GetNodeHostDepsProgressResult
    data object AgentUnreachable : GetNodeHostDepsProgressResult
    data object InvalidAgentKey : GetNodeHostDepsProgressResult
    data object JobNotFound : GetNodeHostDepsProgressResult
}

data class HostDepSpec(
    val id: String,
    val kind: String,
    val name: String = "",
    val javaMajor: Int = 0,
)

sealed interface ProbeHostDepsOnHostResult
{
    data class Ok(val items: List<Pair<String, Boolean>>) : ProbeHostDepsOnHostResult
    data object Unauthorized : ProbeHostDepsOnHostResult
    data object Unreachable : ProbeHostDepsOnHostResult
}

sealed interface StartHostDepsOnHostResult
{
    data class Ok(val jobId: String) : StartHostDepsOnHostResult
    data object Unauthorized : StartHostDepsOnHostResult
    data object Unreachable : StartHostDepsOnHostResult
}

sealed interface FetchHostDepsProgressOnHostResult
{
    data class Ok(val progress: NodeHostDepsProgress) : FetchHostDepsProgressOnHostResult
    data object Unauthorized : FetchHostDepsProgressOnHostResult
    data object Unreachable : FetchHostDepsProgressOnHostResult
    data object JobNotFound : FetchHostDepsProgressOnHostResult
}

fun interface ProbeHostDepsOnHost
{
    suspend fun probe(agentUrl: String, token: String, deps: List<HostDepSpec>): ProbeHostDepsOnHostResult
}

fun interface StartHostDepsOnHost
{
    suspend fun start(agentUrl: String, token: String, jobId: String, deps: List<HostDepSpec>): StartHostDepsOnHostResult
}

fun interface FetchHostDepsProgressOnHost
{
    suspend fun progress(agentUrl: String, token: String, jobId: String): FetchHostDepsProgressOnHostResult
}

/** Common packages every host should have (Go tipops common list). */
val COMMON_HOST_PACKAGES: List<String> = listOf(
    "ca-certificates",
    "curl",
    "wget",
    "tar",
    "jq",
)
