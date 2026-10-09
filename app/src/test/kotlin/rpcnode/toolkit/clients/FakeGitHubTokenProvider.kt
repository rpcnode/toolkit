package rpcnode.toolkit.clients

import rpcnode.toolkit.clients.application.GitHubTokenProvider

class FakeGitHubTokenProvider(
    private val token: String? = "fake-token",
) : GitHubTokenProvider
{
    override suspend fun current(): String? = token
}
