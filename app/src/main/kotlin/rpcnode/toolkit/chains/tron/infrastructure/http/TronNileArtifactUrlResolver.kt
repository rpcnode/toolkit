package rpcnode.toolkit.chains.tron.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.version.ClientArtifactUrlResolver
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec

/**
 * Nile release assets embed the version in the file name, in lower case and without the tag
 * prefix: tag `GreatVoyage-Nile-v4.8.2.2-PQ1-build1` ships `FullNode-Nile-x64-4.8.2.2-pq1-build1.jar`.
 * A fixed name in `clients.yml` 404s as soon as "latest" moves past the pinned version.
 */
class TronNileArtifactUrlResolver : ClientArtifactUrlResolver
{
    override suspend fun resolve(
        spec: ClientProgramSpec,
        artifact: ClientArtifactSpec,
        version: String,
        tag: String,
        aarch64: Boolean,
    ): String?
    {
        if (spec.network != NetworkId.TRON || spec.env != EnvId.NILE)
        {
            return null
        }
        if (!artifact.name.endsWith(".jar", ignoreCase = true))
        {
            return null
        }
        val fileVersion = tag.substringAfter("-v", missingDelimiterValue = "").lowercase()
        if (fileVersion.isEmpty())
        {
            return null
        }
        val arch = if (aarch64) "aarch64" else "x64"
        return "https://github.com/$REPO/releases/download/$tag/FullNode-Nile-$arch-$fileVersion.jar"
    }

    private companion object
    {
        const val REPO = "tron-nile-testnet/nile-testnet"
    }
}
