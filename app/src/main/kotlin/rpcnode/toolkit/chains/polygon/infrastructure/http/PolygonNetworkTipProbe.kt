package rpcnode.toolkit.chains.polygon.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Public tip = same `eth_blockNumber` call against YAML `publicTip.urls`. */
class PolygonNetworkTipProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    override suspend fun tip(urls: List<String>): Long?
    {
        for (url in urls)
        {
            if (url.isBlank()) continue
            return PolygonBorRpc.blockNumber(http, url) ?: continue
        }
        return null
    }
}
