package rpcnode.toolkit.chains.aptos.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Latest `aptos-node-v*` tag of aptos-labs/aptos-core (the repo also carries `aptos-cli-v*`). Mainnet takes the newest
 * stable tag; testnet follows the release candidates it runs.
 */
class AptosClientReleaseResolver(
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        val includePrerelease = when (env)
        {
            EnvId.MAINNET -> false
            EnvId.TESTNET -> true
            else -> return null
        }
        val release = github.latestRelease(REPO, tagPrefix = TAG_PREFIX, includePrerelease = includePrerelease) ?: return null
        if (release.assets.isNotEmpty() && release.assets.none { it.name == ASSET })
        {
            return null
        }
        return ClientRelease(version = release.tag.removePrefix(TAG_PREFIX), tag = release.tag, sourceLabel = REPO)
    }

    companion object
    {
        const val REPO = "aptos-labs/aptos-core"
        const val TAG_PREFIX = "aptos-node-v"
        const val ASSET = "aptos-node-performance-ubuntu-22.04.tgz"
    }
}
