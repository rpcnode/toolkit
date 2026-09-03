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
        val release = githubReleaseClient.latestRelease(source.repo, source.tagPrefix)
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
 * Latest for probe/download: per-network [ClientReleaseResolver] when it matches this program's
 * GitHub repo (or the program is pinned). Otherwise YAML [ClientVersionSource] wins — needed for
 * multi-program networks (ethereum geth + lighthouse).
 */
suspend fun resolveClientRelease(
    spec: ClientProgramSpec,
    githubReleaseClient: GitHubReleaseClient,
    clientReleaseResolvers: Map<NetworkId, ClientReleaseResolver>,
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
        return ResolvedVersion(version = release.version, tag = release.tag, sourceLabel = release.sourceLabel)
    }
    return resolveVersion(spec.source, githubReleaseClient)
}
