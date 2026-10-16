package rpcnode.toolkit.chains.evmfamily.infrastructure.http

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.cometbft.infrastructure.http.CometbftSnapshotResolver
import rpcnode.toolkit.clients.application.version.ClientArtifactUrlResolver
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec

/**
 * Kaia's GitHub releases carry no assets: `ken` lives on packages.kaia.io as `ken-<tag>-<build>-linux-<arch>.tar.gz`.
 * The build number is `0` for every tag checked (v2.0.0 … v2.2.2) but v2.2.1 has no package at all, and the host answers
 * 200 with a ONE-byte body for a path it does not know — so a candidate only counts when its Content-Length is above 1.
 * No candidate = the release has no `ken` package yet: the download fails with that sentence instead of saving the stub.
 */
class KaiaPackageUrlResolver(
    private val contentLength: suspend (String) -> Long? = CometbftSnapshotResolver.Companion::headLength,
    private val builds: List<Int> = listOf(0, 1, 2),
) : ClientArtifactUrlResolver
{
    override suspend fun resolve(
        spec: ClientProgramSpec,
        artifact: ClientArtifactSpec,
        version: String,
        tag: String,
        aarch64: Boolean,
    ): String?
    {
        if (spec.network != NetworkId.KAIA) return null
        val template = if (aarch64 && !artifact.urlTemplateAarch64.isNullOrBlank()) artifact.urlTemplateAarch64 else artifact.urlTemplate
        val base = template.replace("{version}", version).replace("{tag}", tag)
        for (candidate in candidates(base))
        {
            if ((contentLength(candidate) ?: 0L) > 1L) return candidate
        }
        throw IllegalStateException("packages.kaia.io has no ken package for $tag (the release is tagged on GitHub but not published yet)")
    }

    /** `…/ken-v2.2.2-0-linux-amd64.tar.gz` → the same with the build number 0, 1, 2. */
    fun candidates(url: String): List<String> =
        builds.map { url.replace(Regex("""-\d+-linux-"""), "-$it-linux-") }.distinct()
}

