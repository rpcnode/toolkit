package rpcnode.toolkit.chains.bitcoin.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Public Bitcoin tip via mempool.space-style plain-text height endpoints. */
class BitcoinNetworkTipProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    override suspend fun tip(urls: List<String>): Long?
    {
        for (url in urls)
        {
            val u = url.trim()
            if (u.isEmpty()) continue
            val body = http.getText(u, accept = "text/plain, application/json") ?: continue
            val height = body.trim().lines().firstOrNull()?.trim()?.toLongOrNull() ?: continue
            return height
        }
        return null
    }
}
