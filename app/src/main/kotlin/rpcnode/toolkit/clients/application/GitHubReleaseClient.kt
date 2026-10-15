package rpcnode.toolkit.clients.application

data class GitHubReleaseAsset(
    val name: String,
    val browserDownloadUrl: String,
)

data class GitHubRelease(
    val tag: String,
    val version: String,
    val assets: List<GitHubReleaseAsset> = emptyList(),
    /** Release notes markdown (e.g. Docker image pins). */
    val body: String = "",
    val prerelease: Boolean = false,
)

/** Probes the GitHub Releases API for the newest non-draft, non-prerelease release of a repo. */
fun interface GitHubReleaseClient
{
    suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease?

    /** Same, optionally counting release candidates. Clients that cannot tell keep the stable-only answer. */
    suspend fun latestRelease(repo: String, tagPrefix: String?, includePrerelease: Boolean): GitHubRelease? =
        latestRelease(repo, tagPrefix)

    /**
     * Same with a [ReleaseFilter]. Clients that cannot apply it fall back to the plain answer, which is only right
     * for filters without a suffix / semver rule — the HTTP client implements the full filter.
     */
    suspend fun latestRelease(repo: String, filter: ReleaseFilter): GitHubRelease? =
        latestRelease(repo, filter.tagPrefix, filter.includePrerelease)

    /**
     * The newest [perPage] non-draft releases (prereleases included, flagged), newest first, with their assets.
     * For resolvers whose rule needs more than a tag filter (the release must carry certain assets, the tag has its own
     * ordering). Clients that cannot list answer an empty list.
     */
    suspend fun releases(repo: String, perPage: Int = 60): List<GitHubRelease> = emptyList()
}

/**
 * Which release of a repo counts as "latest".
 * [tagSuffix] keeps only tags ending in it (`-mocha`); without it, tags that end in a `-<letters>` network suffix
 * are kept only when [includePrerelease] (they are the testnet line).
 * [highestSemver] picks the highest version among the matches instead of the newest by date — projects that publish
 * a patch for an older line after the newer line would otherwise install the old one.
 * [requireAsset] is a glob (`*`, `?`) over asset names: a release without a matching asset is skipped, so a tag whose
 * per-network files are published later is not picked while those files are still missing.
 */
data class ReleaseFilter(
    val tagPrefix: String? = null,
    val includePrerelease: Boolean = false,
    val tagSuffix: String? = null,
    val highestSemver: Boolean = false,
    val requireAsset: String? = null,
)

/** `*` and `?` are the only wildcards; the whole [name] must match. */
fun assetGlobMatches(glob: String, name: String): Boolean =
    Regex(
        glob.map { c ->
            when (c)
            {
                '*' -> ".*"
                '?' -> "."
                else -> Regex.escape(c.toString())
            }
        }.joinToString(""),
    ).matches(name)
