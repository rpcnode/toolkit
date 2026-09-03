package rpcnode.toolkit.chains.xrpl.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Latest XRPLF/rippled tag for the Ripple apt `.deb` pool.
 * Skips broken 3.2.x (first-ledger never finalizes — XRPLF#7572).
 */
class XrplClientReleaseResolver(
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
        if (isBroken32(release.version) || isBroken32(release.tag))
        {
            return ClientRelease(
                version = FALLBACK_VERSION,
                tag = FALLBACK_VERSION,
                sourceLabel = REPO,
            )
        }
        return ClientRelease(version = release.version, tag = release.tag, sourceLabel = REPO)
    }

    companion object
    {
        const val REPO = "XRPLF/rippled"
        const val FALLBACK_VERSION = "3.3.0"
        private val ENVS = setOf(EnvId.MAINNET, EnvId.TESTNET)

        fun isBroken32(ver: String): Boolean
        {
            val v = ver.trim().lowercase()
            return v.contains("3.2.0") || v.contains("3.2.1")
        }
    }
}
