package rpcnode.toolkit.chains.polkadot.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateSpecs
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Newest `polkadot-stable<YYMM>[-<n>]` release of paritytech/polkadot-sdk that is not a prerelease and carries all three
 * binaries (the node and both PVF workers). Ordered by (YYMM, n) — never by date: Parity back-patches old branches
 * (`polkadot-stable2603-7` was published after `polkadot-stable2609`, with 2 assets), and a worker-less release cannot start.
 */
class PolkadotClientReleaseResolver(
    private val github: GitHubReleaseClient,
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (SubstrateSpecs.POLKADOT.envs.none { it.id == env.value }) return null
        val release = pick(github.releases(REPO)) ?: return null
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = REPO)
    }

    companion object
    {
        const val REPO = "paritytech/polkadot-sdk"
        val ASSETS = SubstrateSpecs.POLKADOT.binaries
        private val TAG = Regex("""^polkadot-stable(\d{4})(?:-(\d+))?$""")

        /** (YYMM, patch) of a tag, or null when it is not a node release tag. */
        fun rank(tag: String): Pair<Int, Int>?
        {
            val m = TAG.matchEntire(tag) ?: return null
            return m.groupValues[1].toInt() to (m.groupValues[2].toIntOrNull() ?: 0)
        }

        fun pick(releases: List<GitHubRelease>): GitHubRelease? =
            releases
                .filter { !it.prerelease }
                .filter { r -> ASSETS.all { name -> r.assets.any { it.name == name } } }
                .mapNotNull { r -> rank(r.tag)?.let { r to it } }
                .maxWithOrNull(compareBy({ it.second.first }, { it.second.second }))
                ?.first
    }
}
