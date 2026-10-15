package rpcnode.toolkit.chains.bsc.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.ReleaseFilter
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/** Latest bnb-chain/bsc tag from GitHub that already carries the genesis/config zip of [env] (geth binary + zip). */
class BscClientReleaseResolver(
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        val asset = CONFIG_ASSET[env] ?: return null
        val release = github.latestRelease(REPO, ReleaseFilter(requireAsset = asset)) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = REPO)
    }

    companion object
    {
        const val REPO = "bnb-chain/bsc"
        private val CONFIG_ASSET = mapOf(
            EnvId.MAINNET to "mainnet.zip",
            EnvId.TESTNET to "testnet.zip",
        )
    }
}
