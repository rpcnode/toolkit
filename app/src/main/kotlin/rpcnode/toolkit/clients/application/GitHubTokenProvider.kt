package rpcnode.toolkit.clients.application

/** Plaintext GitHub PAT for authenticated API/download calls, or null when none is configured. */
fun interface GitHubTokenProvider
{
    suspend fun current(): String?
}
