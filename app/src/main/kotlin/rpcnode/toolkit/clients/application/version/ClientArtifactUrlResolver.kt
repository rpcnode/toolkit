package rpcnode.toolkit.clients.application.version

import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec

/**
 * Optional per-network override for artifact download URLs (e.g. gethstore blob listing).
 * Return null to fall back to `{version}`/`{tag}` template substitution.
 */
fun interface ClientArtifactUrlResolver
{
    suspend fun resolve(
        spec: ClientProgramSpec,
        artifact: ClientArtifactSpec,
        version: String,
        tag: String,
        aarch64: Boolean,
    ): String?
}
