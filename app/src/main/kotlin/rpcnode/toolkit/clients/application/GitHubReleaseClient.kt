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
)

/** Probes the GitHub Releases API for the newest non-draft, non-prerelease release of a repo. */
fun interface GitHubReleaseClient
{
    suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease?
}
