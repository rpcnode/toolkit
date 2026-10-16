package rpcnode.toolkit.chains.avalanche.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Latest ava-labs/avalanchego tag. Mainnet takes the newest stable release; Fuji also takes the `-fuji`
 * pre-releases, which carry an upgrade before it reaches mainnet.
 */
class AvalancheClientReleaseResolver(
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        val includePrerelease = when (env)
        {
            EnvId.MAINNET -> false
            EnvId.FUJI -> true
            else -> return null
        }
        val release = github.latestRelease(REPO, tagPrefix = null, includePrerelease = includePrerelease) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = REPO)
    }

    companion object
    {
        const val REPO = "ava-labs/avalanchego"
    }
}
