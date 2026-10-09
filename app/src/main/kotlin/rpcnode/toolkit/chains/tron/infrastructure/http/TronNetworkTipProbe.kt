package rpcnode.toolkit.chains.tron.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Public tip = same height call against YAML `publicTip.urls`
 * (e.g. `https://api.shasta.trongrid.io/wallet/getnowblock`).
 */
class TronNetworkTipProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    override suspend fun tip(urls: List<String>): Long?
    {
        for (url in urls)
        {
            if (url.isBlank()) continue
            return http.tronGetNowBlockHeight(url) ?: continue
        }
        return null
    }
}
