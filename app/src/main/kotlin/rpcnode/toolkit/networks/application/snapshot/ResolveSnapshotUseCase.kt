package rpcnode.toolkit.networks.application.snapshot

import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.SnapshotArchive

sealed interface ResolveSnapshotResult
{
    /** [archive] is null when this env has no snapshot or the network had nothing resolvable. */
    data class Resolved(val archive: SnapshotArchive?, val typeId: String) : ResolveSnapshotResult
    data object UnknownNetwork : ResolveSnapshotResult
    data object UnknownEnv : ResolveSnapshotResult
}

/**
 * Live snapshot archive for one network + env + type. Callers that need it invoke this; listing
 * networks does not scrape mirrors. A missing resolver or a null scrape is [Resolved] with
 * `archive = null`, not an error.
 */
class ResolveSnapshotUseCase(
    private val catalog: NetworkCatalog,
    private val snapshotResolvers: Map<NetworkId, SnapshotResolver>,
)
{
    suspend operator fun invoke(
        networkRaw: String,
        envRaw: String,
        typeId: String = "",
    ): ResolveSnapshotResult
    {
        val networkId = NetworkId.parse(networkRaw) ?: return ResolveSnapshotResult.UnknownNetwork
        val chain = catalog.find(networkId) ?: return ResolveSnapshotResult.UnknownNetwork
        val env = chain.env(envRaw) ?: return ResolveSnapshotResult.UnknownEnv
        val type = typeId.trim().lowercase()
        val archive = snapshotResolvers[networkId]?.resolve(env.id, type)
        return ResolveSnapshotResult.Resolved(archive = archive, typeId = type)
    }
}
