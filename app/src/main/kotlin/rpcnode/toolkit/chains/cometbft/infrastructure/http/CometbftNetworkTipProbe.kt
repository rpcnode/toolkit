package rpcnode.toolkit.chains.cometbft.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Public tip = `/status` of the first public RPC (YAML `publicTip.urls`) that is not catching up. */
class CometbftNetworkTipProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    override suspend fun tip(urls: List<String>): Long?
    {
        for (url in urls)
        {
            if (url.isBlank()) continue
            val s = CometbftStatus.fetch(http, url) ?: continue
            if (!s.catchingUp && s.height > 0) return s.height
        }
        return null
    }
}
