package rpcnode.toolkit.clients.application.version

import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.resolveClientRelease
import rpcnode.toolkit.clients.domain.model.ClientRelease
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog

sealed interface ResolveClientReleaseResult
{
    /** [release] is null when this env has no client or the network had nothing resolvable. */
    data class Resolved(val release: ClientRelease?) : ResolveClientReleaseResult
    data object UnknownNetwork : ResolveClientReleaseResult
    data object UnknownEnv : ResolveClientReleaseResult
}

/**
 * Latest client release for one network + env. Prefer the dedicated [ClientReleaseResolver],
 * then the same YAML GitHub/pinned source path used by probe/download
 * ([resolveClientRelease]). Listing clients does not hit GitHub.
 */
class ResolveClientReleaseUseCase(
    private val catalog: NetworkCatalog,
    private val clientReleaseResolvers: Map<NetworkId, ClientReleaseResolver>,
    private val programs: ClientProgramCatalog,
    private val github: GitHubReleaseClient,
)
{
    suspend operator fun invoke(networkRaw: String, envRaw: String): ResolveClientReleaseResult
    {
        val networkId = NetworkId.parse(networkRaw) ?: return ResolveClientReleaseResult.UnknownNetwork
        val chain = catalog.find(networkId) ?: return ResolveClientReleaseResult.UnknownNetwork
        val env = chain.env(envRaw) ?: return ResolveClientReleaseResult.UnknownEnv
        val fromResolver = clientReleaseResolvers[networkId]?.resolve(env.id)
        if (fromResolver != null)
        {
            return ResolveClientReleaseResult.Resolved(fromResolver)
        }
        val program = programs.programsFor(networkId, env.id).firstOrNull()
            ?: return ResolveClientReleaseResult.Resolved(null)
        val resolved = resolveClientRelease(program, github, clientReleaseResolvers)
        if (resolved.version.isBlank())
        {
            return ResolveClientReleaseResult.Resolved(null)
        }
        return ResolveClientReleaseResult.Resolved(
            ClientRelease(
                version = resolved.version,
                tag = resolved.tag,
                sourceLabel = resolved.sourceLabel,
            ),
        )
    }
}
