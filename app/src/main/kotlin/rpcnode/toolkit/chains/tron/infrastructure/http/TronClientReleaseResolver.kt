package rpcnode.toolkit.chains.tron.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * java-tron FullNode. Mainnet and Shasta share [MAINNET_REPO]; Nile is a separate testnet
 * repo and must not reuse mainnet tags. Latest is the newest non-draft GitHub release.
 */
class TronClientReleaseResolver(
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        val repo = REPOS[env] ?: return null
        val release = github.latestRelease(repo, tagPrefix = null) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = repo)
    }

    companion object
    {
        private const val MAINNET_REPO = "tronprotocol/java-tron"
        private const val NILE_REPO = "tron-nile-testnet/nile-testnet"
        private val REPOS = mapOf(
            EnvId.MAINNET to MAINNET_REPO,
            EnvId.SHASTA to MAINNET_REPO,
            EnvId.NILE to NILE_REPO,
        )
    }
}
