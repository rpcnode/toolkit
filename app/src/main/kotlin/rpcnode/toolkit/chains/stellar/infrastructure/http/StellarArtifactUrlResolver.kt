package rpcnode.toolkit.chains.stellar.infrastructure.http

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.version.ClientArtifactUrlResolver
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec

/**
 * Resolves the `stellar-apt:<package>[:<suite>]` artifact markers of `clients.yml` to the newest stable `.deb` URL of
 * apt.stellar.org (the file names carry apt build numbers, so a plain `{tag}` template cannot name them).
 */
class StellarArtifactUrlResolver(
    private val apt: StellarAptIndex,
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
        if (spec.network != NetworkId.STELLAR)
        {
            return null
        }
        val template = artifact.urlTemplate.trim()
        if (!template.startsWith(MARKER))
        {
            return null
        }
        val parts = template.removePrefix(MARKER).split(':')
        val pkg = parts[0]
        val suite = parts.getOrNull(1)?.ifBlank { null } ?: "noble"
        val found = apt.newest(pkg, suite) ?: throw IllegalStateException("apt.stellar.org: no stable $pkg for $suite")
        return "https://apt.stellar.org/${found.filename}"
    }

    companion object
    {
        const val MARKER = "stellar-apt:"
    }
}
