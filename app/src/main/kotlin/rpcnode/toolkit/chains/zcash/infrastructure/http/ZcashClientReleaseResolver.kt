package rpcnode.toolkit.chains.zcash.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/** Latest zcashd release tag from GitHub (binaries ship from download.z.cash). */
class ZcashClientReleaseResolver(
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
        private const val REPO = "zcash/zcash"
        private val ENVS = setOf(EnvId.MAINNET, EnvId.TESTNET, EnvId.REGTEST)
    }
}
