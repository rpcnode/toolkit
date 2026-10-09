package rpcnode.toolkit.chains.xrpl.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * Latest XRPLF/rippled tag that the Ripple apt `.deb` pool actually ships. A GitHub release can
 * land weeks before its package reaches the pool (3.4.0 vs pool newest 3.3.0), so the tag is
 * capped to the newest pooled version.
 * Skips broken 3.2.x (first-ledger never finalizes — XRPLF#7572).
 */
class XrplClientReleaseResolver(
    private val github: GitHubReleaseClient,
    private val pool: XrplDebPool = HttpXrplDebPool(),
) : ClientReleaseResolver
{
    override suspend fun resolve(env: EnvId): ClientRelease?
    {
        if (env !in ENVS)
        {
            return null
        }
        val release = github.latestRelease(REPO, tagPrefix = null) ?: return null
        val pooled = pool.versions()?.filterNot(::isBroken32)
        val version = when
        {
            pooled != null && release.version !in pooled ->
                pooled.maxWithOrNull(::compareVersions) ?: return null
            isBroken32(release.version) || isBroken32(release.tag) -> FALLBACK_VERSION
            else -> return ClientRelease(version = release.version, tag = release.tag, sourceLabel = REPO)
        }
        return ClientRelease(version = version, tag = version, sourceLabel = REPO)
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

        private fun compareVersions(a: String, b: String): Int
        {
            val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size))
            {
                val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
