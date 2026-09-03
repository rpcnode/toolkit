package rpcnode.toolkit.clients

import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseClient

class FakeGitHubReleaseClient(
    private val byRepo: Map<String, GitHubRelease> = emptyMap(),
) : GitHubReleaseClient
{
    override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease? = byRepo[repo]
}
