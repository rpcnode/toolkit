package rpcnode.toolkit.chains.ethereum.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Latest geth tag from GitHub (binaries come from gethstore). Used by Add-client version step.
 * Lighthouse keeps its own GitHub source in `clients/ethereum.yml` — download must not reuse this tag.
 */
class EthereumClientReleaseResolver(
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env !in ENVS)
        {
            return null
        }
        val release = github.latestRelease(REPO, tagPrefix = null) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = REPO)
    }

    companion object
    {
        const val REPO = "ethereum/go-ethereum"
        private val ENVS = setOf(EnvId.MAINNET, EnvId.SEPOLIA, EnvId.HOODI)
    }
}
