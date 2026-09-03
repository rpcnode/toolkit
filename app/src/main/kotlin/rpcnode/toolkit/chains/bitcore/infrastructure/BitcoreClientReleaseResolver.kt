package rpcnode.toolkit.chains.bitcore.infrastructure

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/** Latest GitHub release for a bitcoind-style fork (one tarball per release, all envs). */
class BitcoreClientReleaseResolver(
    private val spec: BitcoreChainSpec,
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env !in spec.supportedEnvs)
        {
            return null
        }
        val release = github.latestRelease(spec.githubRepo, tagPrefix = null) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = spec.githubRepo)
    }
}
