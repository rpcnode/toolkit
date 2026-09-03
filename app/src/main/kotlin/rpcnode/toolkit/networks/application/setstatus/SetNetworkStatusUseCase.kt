package rpcnode.toolkit.networks.application.setstatus

import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.NetworkStatus
import rpcnode.toolkit.networks.domain.repository.NetworkRepository

sealed interface SetNetworkStatusResult
{
    data class Ok(val network: NetworkId, val status: NetworkStatus) : SetNetworkStatusResult
    data object UnknownNetwork : SetNetworkStatusResult
    data object BadAction : SetNetworkStatusResult
}

/**
 * Enable ("ready"), hide ("skip") or park ("pending") a network for this install. No files-on-disk
 * check here — the operator decides; readiness (`files_ready`/`pin_only`) is only informational.
 */
class SetNetworkStatusUseCase(
    private val catalog: NetworkCatalog,
    private val networkRepo: NetworkRepository,
)
{
    suspend operator fun invoke(network: String, action: String): SetNetworkStatusResult
    {
        val id = NetworkId.parse(network) ?: return SetNetworkStatusResult.UnknownNetwork
        catalog.find(id) ?: return SetNetworkStatusResult.UnknownNetwork
        val status = when (action.trim())
        {
            "enable" -> NetworkStatus.READY
            "skip" -> NetworkStatus.SKIPPED
            "pending" -> NetworkStatus.PENDING
            else -> return SetNetworkStatusResult.BadAction
        }
        networkRepo.upsert(id, status, notes = "")
        return SetNetworkStatusResult.Ok(id, status)
    }
}
