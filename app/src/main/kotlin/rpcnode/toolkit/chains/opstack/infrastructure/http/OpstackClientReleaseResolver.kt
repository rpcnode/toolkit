package rpcnode.toolkit.chains.opstack.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.ReleaseFilter
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackToolchain

/**
 * World Chain's own reth fork: the highest stable `vX.Y.Z` release of worldcoin/world-chain. The repo also tags `proofs/v…`
 * (and `-rc` tags): the `v` prefix drops the former, the stable-only filter the latter. op-reth / op-node are pinned
 * (`source: pinned`), they have no resolver.
 */
class OpstackClientReleaseResolver(
    private val github: GitHubReleaseClient,
    private val repo: String = OpstackToolchain.WORLD_CHAIN_REPO,
    private val envs: Set<EnvId> = setOf(EnvId.MAINNET, EnvId.SEPOLIA),
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env !in envs) return null
        val release = github.latestRelease(repo, ReleaseFilter(tagPrefix = "v", highestSemver = true)) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = repo)
    }
}
