package rpcnode.toolkit.networks.application.install

import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.application.ClientFilesReadyChecker
import rpcnode.toolkit.networks.domain.model.NetworkPinOnly

sealed interface CheckNetworkInstallResult
{
    data class FilesOk(val network: NetworkId, val source: String, val pinOnly: Boolean) : CheckNetworkInstallResult
    data object UnknownNetwork : CheckNetworkInstallResult
    data object ClientRequired : CheckNetworkInstallResult
}

/** Pre-flight check: are this network's client files already on disk (or is it pin-only)? */
class CheckNetworkInstallUseCase(
    private val catalog: NetworkCatalog,
    private val filesReady: ClientFilesReadyChecker,
)
{
    suspend operator fun invoke(network: String): CheckNetworkInstallResult
    {
        val id = NetworkId.parse(network) ?: return CheckNetworkInstallResult.UnknownNetwork
        val chain = catalog.find(id) ?: return CheckNetworkInstallResult.UnknownNetwork
        val pinOnly = NetworkPinOnly.isPinOnly(id)
        val ready = pinOnly || filesReady.ready(id, chain.envs.map { it.id })
        if (!ready)
        {
            return CheckNetworkInstallResult.ClientRequired
        }
        return CheckNetworkInstallResult.FilesOk(id, source = if (pinOnly) "pin" else "disk", pinOnly = pinOnly)
    }
}
