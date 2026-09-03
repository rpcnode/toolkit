package rpcnode.toolkit.chains.ton.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Public tip = same `getMasterchainInfo` call against YAML `publicTip.urls` (toncenter).
 */
class TonNetworkTipProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    override suspend fun tip(urls: List<String>): Long?
    {
        for (url in urls)
        {
            if (url.isBlank()) continue
            val n = TonGetMasterchainInfo.seqno(http, url) ?: continue
            if (n > 0)
            {
                return n
            }
        }
        return null
    }
}
