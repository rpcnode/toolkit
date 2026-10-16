package rpcnode.toolkit.chains.nonevm.infrastructure

import rpcnode.toolkit.clients.application.GitHubRelease

/**
 * Release-ranking rules the family shares. GitHub lists releases by date, but the projects here publish a patch for an
 * older line after the newer one (fuel `v0.47.4` after `v0.48.0`, polkadot `polkadot-stable2603-7` after
 * `polkadot-stable2609`), so "latest" is the highest version, never the newest date.
 */
object NonEvmReleases
{
    /** `parts` of a dotted version and, for a release candidate, its number; `rc == null` is a final release. */
    data class Version(val parts: List<Long>, val rc: Long? = null) : Comparable<Version>
    {
        override fun compareTo(other: Version): Int
        {
            for (i in 0 until maxOf(parts.size, other.parts.size))
            {
                val c = parts.getOrElse(i) { 0L }.compareTo(other.parts.getOrElse(i) { 0L })
                if (c != 0) return c
            }
            // a release candidate is older than the final release of the same version
            return (rc ?: Long.MAX_VALUE).compareTo(other.rc ?: Long.MAX_VALUE)
        }
    }

    private val DOTTED = Regex("""^(\d+(?:\.\d+)+)(?:-rc\.(\d+))?$""")

    /** `2.13.4`, `4.0.5`, `3.4.0.0.4`, `2.14.0-rc.3` after [prefix] (`v`, …). Null for anything else (`signer-3.4.0.0.4.0`, `testnet-v4.12.0`). */
    fun parse(tag: String, prefix: String = ""): Version?
    {
        if (!tag.startsWith(prefix)) return null
        val m = DOTTED.matchEntire(tag.removePrefix(prefix).trim()) ?: return null
        return Version(m.groupValues[1].split('.').map { it.toLong() }, m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong())
    }

    /**
     * Releases whose tag parses after [prefix], that are not prereleases unless [includePrerelease], and that carry every
     * asset [requiredAssets] names for them — highest version first.
     */
    fun ranked(
        releases: List<GitHubRelease>,
        prefix: String = "",
        includePrerelease: Boolean = false,
        requiredAssets: (GitHubRelease) -> List<String> = { emptyList() },
    ): List<GitHubRelease> =
        releases
            .filter { includePrerelease || !it.prerelease }
            .filter { r -> requiredAssets(r).all { name -> r.assets.any { it.name == name } } }
            .mapNotNull { r -> parse(r.tag, prefix)?.let { r to it } }
            .sortedByDescending { it.second }
            .map { it.first }

    fun highest(
        releases: List<GitHubRelease>,
        prefix: String = "",
        includePrerelease: Boolean = false,
        requiredAssets: (GitHubRelease) -> List<String> = { emptyList() },
    ): GitHubRelease? = ranked(releases, prefix, includePrerelease, requiredAssets).firstOrNull()
}
