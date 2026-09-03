package rpcnode.toolkit.chains.sui.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/** Latest MystenLabs/sui tag for mainnet- / testnet- channels. */
class SuiClientReleaseResolver(
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        val prefix = PREFIX[env] ?: return null
        val release = github.latestRelease(REPO, tagPrefix = prefix) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = REPO)
    }

    companion object
    {
        const val REPO = "MystenLabs/sui"
        private val PREFIX = mapOf(
            EnvId.MAINNET to "mainnet-",
            EnvId.TESTNET to "testnet-",
        )
    }
}
