package rpcnode.toolkit.networks.application.tip

import rpcnode.toolkit.catalog.domain.NetworkId

/** Public tip from YAML `publicTip.urls` — same height protocol as the local node probe. */
fun interface NetworkTipProbe
{
    suspend fun tip(urls: List<String>): Long?
}

/** Registry of chain tip probes (panel-side, not host agent). */
class NetworkTipProbeRegistry(
    private val byNetwork: Map<NetworkId, NetworkTipProbe>,
)
{
    fun forNetwork(id: NetworkId): NetworkTipProbe? = byNetwork[id]
}
