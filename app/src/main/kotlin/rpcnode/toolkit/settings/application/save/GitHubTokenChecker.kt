package rpcnode.toolkit.settings.application.save

fun interface GitHubTokenChecker
{
    suspend fun check(token: String): GitHubTokenCheck
}

sealed interface GitHubTokenCheck
{
    data object Ok : GitHubTokenCheck
    data object Rejected : GitHubTokenCheck
    data class Failed(val reason: String) : GitHubTokenCheck
}
