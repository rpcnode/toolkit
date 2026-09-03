package rpcnode.toolkit.chains.ethereum.infrastructure.http

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.version.ClientArtifactUrlResolver
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec

/** Resolves geth linux tarball URLs via Azure gethstore listing. */
class EthereumGethArtifactUrlResolver(
    private val gethstore: GethstoreBlobClient = GethstoreBlobClient(),
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
        if (spec.network != NetworkId.ETHEREUM)
        {
            return null
        }
        if (!spec.programId.equals("geth", ignoreCase = true))
        {
            return null
        }
        val template = if (aarch64 && !artifact.urlTemplateAarch64.isNullOrBlank())
        {
            artifact.urlTemplateAarch64
        }
        else
        {
            artifact.urlTemplate
        }
        if (!template.trim().equals("gethstore", ignoreCase = true))
        {
            return null
        }
        return gethstore.resolveLinuxTarballUrl(version.ifBlank { tag }, aarch64)
            ?: throw IllegalStateException("gethstore: no linux tarball for geth $version")
    }
}
