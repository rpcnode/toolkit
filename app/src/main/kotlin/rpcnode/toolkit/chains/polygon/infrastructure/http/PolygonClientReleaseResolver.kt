package rpcnode.toolkit.chains.polygon.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.ReleaseFilter
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Latest bor tag from GitHub that already carries the sentry config deb of [env] — the per-network files are part of
 * the install, so a tag still missing them is not offered. Heimdall keeps its own GitHub source in `clients/polygon.yml`.
 */
class PolygonClientReleaseResolver(
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
        const val REPO = "0xPolygon/bor"
        private val CONFIG_ASSET = mapOf(
            EnvId.MAINNET to "bor-mainnet-sentry-config_*-all.deb",
            EnvId.AMOY to "bor-amoy-sentry-config_*-all.deb",
        )
    }
}
