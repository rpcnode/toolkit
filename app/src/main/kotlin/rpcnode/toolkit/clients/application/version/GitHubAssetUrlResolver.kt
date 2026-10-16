package rpcnode.toolkit.clients.application.version

import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.ReleaseFilter
import rpcnode.toolkit.clients.application.assetGlobMatches
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionSource

/**
 * `urlTemplate: "github-asset:<glob>"` — the asset of the resolved GitHub release whose file name matches the glob.
 * For releases whose asset names carry something the tag does not give (Nethermind:
 * `nethermind-2.1.0-b3e7e84c-linux-x64.zip` has the commit hash in it). `*` and `?` are the only wildcards.
 */
class GitHubAssetUrlResolver(private val github: GitHubReleaseClient) : ClientArtifactUrlResolver
{
    override suspend fun resolve(
        spec: ClientProgramSpec,
        artifact: ClientArtifactSpec,
        version: String,
        tag: String,
        aarch64: Boolean,
    ): String?
    {
        val template = (if (aarch64 && !artifact.urlTemplateAarch64.isNullOrBlank()) artifact.urlTemplateAarch64 else artifact.urlTemplate).trim()
        val glob = globOf(template) ?: return null
        val source = spec.source as? ClientVersionSource.GitHubRelease
            ?: error("github-asset needs a github source (${spec.network.value}/${spec.programId})")
        val release = github.latestRelease(
            source.repo,
            ReleaseFilter(tagPrefix = source.tagPrefix, includePrerelease = source.includePrerelease, tagSuffix = source.tagSuffix, requireAsset = source.requireAsset),
        )
        return pick(release, tag, glob)
            ?: error("${source.repo} $tag has no asset matching $glob")
    }

    companion object
    {
        const val PREFIX = "github-asset:"

        fun globOf(template: String): String? =
            template.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.trim()?.takeIf { it.isNotEmpty() }

        /** Download URL of the first asset (by name) of [release] matching [glob]; null when the release is not [tag]. */
        fun pick(release: GitHubRelease?, tag: String, glob: String): String?
        {
            if (release == null || (tag.isNotBlank() && release.tag != tag))
            {
                return null
            }
            return release.assets.filter { assetGlobMatches(glob, it.name) }.minByOrNull { it.name }?.browserDownloadUrl?.takeIf { it.isNotBlank() }
        }
    }
}
