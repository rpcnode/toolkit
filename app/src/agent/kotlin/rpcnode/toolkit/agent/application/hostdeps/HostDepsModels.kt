package rpcnode.toolkit.agent.application.hostdeps

/** One host dependency the panel asked the agent to ensure. */
data class HostDepRequest(
    val id: String,
    val kind: String,
    val name: String = "",
    val javaMajor: Int = 0,
)

data class HostDepProbeItem(
    val id: String,
    val present: Boolean,
    val detail: String = "",
)

data class HostDepProgressItem(
    val id: String,
    val status: String,
    val detail: String = "",
)

data class HostDepsJobSnapshot(
    val jobId: String,
    val phase: String,
    val detail: String = "",
    val pct: Int = 0,
    val currentId: String = "",
    val items: List<HostDepProgressItem> = emptyList(),
    val ready: Boolean = false,
    val failed: Boolean = false,
    val error: String = "",
    val logTail: List<String> = emptyList(),
)

sealed interface StartHostDepsInstallResult
{
    data class Started(val jobId: String) : StartHostDepsInstallResult
    data class AlreadyRunning(val jobId: String) : StartHostDepsInstallResult
    data object Invalid : StartHostDepsInstallResult
}
