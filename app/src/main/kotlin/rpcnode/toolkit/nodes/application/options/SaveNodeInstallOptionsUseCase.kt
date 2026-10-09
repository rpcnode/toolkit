package rpcnode.toolkit.nodes.application.options

import java.time.Instant
import rpcnode.toolkit.networks.application.snapshot.mergeInstallOptionsSnapshot
import rpcnode.toolkit.networks.application.snapshot.snapshotTypesFor
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository

sealed interface SaveNodeInstallOptionsResult
{
    data class Saved(val nodeId: String, val installOptionsJson: String) : SaveNodeInstallOptionsResult
    data object NotFound : SaveNodeInstallOptionsResult
    data object InvalidType : SaveNodeInstallOptionsResult
}

class SaveNodeInstallOptionsUseCase(
    private val nodes: NodeRepository,
    private val facts: NetworkFactsRepository,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    /**
     * Persists wizard choices. When [snapshotType] is set, validates against
     * `chains/<id>/network.yml` snapshotTypes and stores under install_options.snapshot.
     */
    suspend operator fun invoke(
        idRaw: String,
        snapshotType: String?,
        installOptionsJson: String? = null,
    ): SaveNodeInstallOptionsResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return SaveNodeInstallOptionsResult.NotFound
        val node = nodes.findById(id) ?: return SaveNodeInstallOptionsResult.NotFound
        val type = snapshotType?.trim()?.lowercase().orEmpty()
        var next = installOptionsJson?.trim()?.takeIf { it.isNotEmpty() } ?: node.installOptionsJson
        if (type.isNotEmpty())
        {
            val allowed = snapshotTypesFor(facts, node.network, node.env.value)
            if (allowed.isNotEmpty() && allowed.none { it.id == type })
            {
                return SaveNodeInstallOptionsResult.InvalidType
            }
            next = mergeInstallOptionsSnapshot(next, type)
        }
        val ok = nodes.saveInstallOptions(id, next, clock())
        return if (ok || node.installOptionsJson == next)
        {
            SaveNodeInstallOptionsResult.Saved(node.id.value, next)
        }
        else
        {
            SaveNodeInstallOptionsResult.NotFound
        }
    }
}
