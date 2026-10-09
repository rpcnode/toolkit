package rpcnode.toolkit.chains.solana.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/** Latest Agave tag from GitHub (anza-xyz/agave). */
class SolanaClientReleaseResolver(
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
        const val REPO = "anza-xyz/agave"
        private val ENVS = setOf(EnvId.MAINNET, EnvId.TESTNET, EnvId.DEVNET)
    }
}
