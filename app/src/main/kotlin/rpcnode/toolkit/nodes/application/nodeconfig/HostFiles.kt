package rpcnode.toolkit.nodes.application.nodeconfig

sealed interface HostFileRead
{
    /** [exists] false = not created yet. */
    data class Ok(val path: String, val exists: Boolean, val content: String) : HostFileRead
    data object Unauthorized : HostFileRead
    data object TooLarge : HostFileRead
    data class Failed(val detail: String) : HostFileRead
}

sealed interface HostFileWrite
{
    data class Ok(val path: String) : HostFileWrite
    data object Unauthorized : HostFileWrite
    data class Failed(val detail: String) : HostFileWrite
}

/** Text files on the managed host, through the agent. Null = agent unreachable. */
interface HostFiles
{
    suspend fun read(agentUrl: String, token: String, path: String): HostFileRead?

    suspend fun write(agentUrl: String, token: String, path: String, content: String): HostFileWrite?
}
