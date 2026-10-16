package rpcnode.toolkit.chains.cometbft.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.ReleaseFilter
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Highest stable release of [repo] (not the newest by date: Sei and XRPL EVM publish patches for an older line after
 * the newer one). An Injective tag ends in `-<unix ts>`; the version drops that build id.
 */
class CometbftClientReleaseResolver(
    private val github: GitHubReleaseClient,
    private val repo: String,
    private val envs: Set<EnvId>,
    private val includePrerelease: (EnvId) -> Boolean = { false },
    private val tagSuffix: (EnvId) -> String? = { null },
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env !in envs) return null
        val filter = ReleaseFilter(
            includePrerelease = includePrerelease(env),
            tagSuffix = tagSuffix(env),
            highestSemver = true,
        )
        val release = github.latestRelease(repo, filter) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = repo)
    }
}
