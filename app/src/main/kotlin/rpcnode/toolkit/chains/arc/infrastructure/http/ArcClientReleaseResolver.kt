package rpcnode.toolkit.chains.arc.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/** Latest stable circlefin/arc-node release (one tarball serves both envs; Circle publishes one version for both). */
class ArcClientReleaseResolver(
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env != EnvId.MAINNET && env != EnvId.TESTNET)
        {
            return null
        }
        val release = github.latestRelease(REPO, tagPrefix = null) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = REPO)
    }

    companion object
    {
        const val REPO = "circlefin/arc-node"
    }
}
