package rpcnode.toolkit.chains.arb.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.arb.infrastructure.docker.ArbNitroDockerTags
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Latest OffchainLabs/nitro release. [ClientRelease.tag] is the Docker Hub image tag
 * (`v3.11.3-beb2108`) for `docker://offchainlabs/nitro-node:{tag}` downloads.
 */
class ArbClientReleaseResolver(
    private val github: GitHubReleaseClient,
    private val hubTags: ArbNitroDockerHubTags = HttpArbNitroDockerHubTags(),
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env !in ENVS)
        {
            return null
        }
        val release = github.latestRelease(REPO, tagPrefix = null) ?: return null
        val dockerTag = ArbNitroDockerTags.fromReleaseBody(release.body)
            ?: ArbNitroDockerTags.pickCanonical(hubTags.listMatching(release.tag), release.tag)
            ?: return null
        return ClientRelease(
            version = release.version,
            tag = dockerTag,
            sourceLabel = REPO,
        )
    }

    companion object
    {
        const val REPO = "OffchainLabs/nitro"
        private val ENVS = setOf(EnvId.MAINNET, EnvId.SEPOLIA)
    }
}
