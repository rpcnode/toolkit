package rpcnode.toolkit.agent.application.update

fun interface AgentReleaseChannel
{
    suspend fun version(panelUrl: String): String?
}

sealed interface AgentInstallResult
{
    data class Ok(val jar: String) : AgentInstallResult
    data class Failed(val message: String) : AgentInstallResult
}

fun interface AgentJarInstaller
{
    suspend fun install(panelUrl: String): AgentInstallResult
}

fun interface AgentRestarter
{
    fun schedule()
}
