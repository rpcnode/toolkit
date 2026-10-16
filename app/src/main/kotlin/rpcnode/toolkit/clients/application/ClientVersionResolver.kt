package rpcnode.toolkit.clients.application

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionSource

data class ResolvedVersion(
    val version: String,
    val tag: String,
    val sourceLabel: String,
    val error: String? = null,
)

/** Shared by probe and download: turns a catalog [ClientVersionSource] into a concrete version/tag. */
suspend fun resolveVersion(source: ClientVersionSource, githubReleaseClient: GitHubReleaseClient): ResolvedVersion = when (source)
{
    is ClientVersionSource.Pinned ->
        ResolvedVersion(version = source.version, tag = source.tag, sourceLabel = source.label)
    is ClientVersionSource.GitHubRelease ->
    {
        val release = githubReleaseClient.latestRelease(
            source.repo,
            ReleaseFilter(tagPrefix = source.tagPrefix, includePrerelease = source.includePrerelease, tagSuffix = source.tagSuffix, requireAsset = source.requireAsset),
        )
        if (release == null)
        {
            ResolvedVersion(version = "", tag = "", sourceLabel = source.repo, error = "no releases found for ${source.repo}")
        }
        else
        {
            ResolvedVersion(version = release.version, tag = release.tag, sourceLabel = source.repo)
        }
    }
}

/**
 * The installed version the downgrade guard of [resolveClientRelease] compares against. Programs of one network can
 * have unrelated version numbers (Optimism: `op-reth/v2.6.0` next to `op-node/v1.19.9`), so an installed pin that belongs
 * to another release line (different `name/` tag prefix than the pinned source) must not make the right pin look old.
 */
fun installedVersionFor(spec: ClientProgramSpec, installedVersion: String, installedTag: String): String
{
    if (installedVersion.isBlank())
    {
        return ""
    }
    val line = (spec.source as? ClientVersionSource.Pinned)?.tag?.substringBefore('/', "").orEmpty()
    val installedLine = installedTag.substringBefore('/', "")
    return if (line.isNotEmpty() && installedLine.isNotEmpty() && line != installedLine) "" else installedVersion
}

/**
 * Latest for probe/download: per-network [ClientReleaseResolver] when it matches this program's
 * GitHub repo (or the program is pinned). Otherwise YAML [ClientVersionSource] wins — needed for
 * multi-program networks (ethereum geth + lighthouse).
 */
suspend fun resolveClientRelease(
    spec: ClientProgramSpec,
    githubReleaseClient: GitHubReleaseClient,
    clientReleaseResolvers: Map<NetworkId, ClientReleaseResolver>,
    installedVersion: String = "",
    installedTag: String = "",
): ResolvedVersion
{
    val release = clientReleaseResolvers[spec.network]?.resolve(spec.env)
    if (release != null)
    {
        when (val source = spec.source)
        {
            is ClientVersionSource.GitHubRelease ->
            {
                if (!release.sourceLabel.equals(source.repo, ignoreCase = true))
                {
                    return resolveVersion(source, githubReleaseClient)
                }
            }
            is ClientVersionSource.Pinned -> Unit
        }
        // The newest release is older than what is installed (a pre-release such as bsc 1.8.0-alpha, or a hand-installed
        // build): "latest" is the installed one, so probe shows no update and Update never installs the older build.
        if (installedVersion.isNotBlank() && isOlderClientVersion(release.version, installedVersion))
        {
            return ResolvedVersion(version = installedVersion, tag = installedTag, sourceLabel = release.sourceLabel)
        }
        return ResolvedVersion(version = release.version, tag = release.tag, sourceLabel = release.sourceLabel)
    }
    val fallback = resolveVersion(spec.source, githubReleaseClient)
    // The live feed was unreachable and the hand-maintained catalog pin is older than what is installed:
    // offering it would downgrade the client (and "Update" would really install the older build).
    if (fallback.error == null && installedVersion.isNotBlank() && isOlderClientVersion(fallback.version, installedVersion))
    {
        return ResolvedVersion(
            version = "",
            tag = "",
            sourceLabel = fallback.sourceLabel,
            error = "release feed unreachable — catalog pin ${fallback.version} is older than the installed $installedVersion, keeping the installed one",
        )
    }
    return fallback
}
