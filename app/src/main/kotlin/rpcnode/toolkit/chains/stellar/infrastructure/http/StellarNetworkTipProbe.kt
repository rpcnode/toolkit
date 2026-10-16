package rpcnode.toolkit.chains.stellar.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Public tip = latest ledger of a public stellar-rpc (`getHealth`, else `getLatestLedger`) from YAML `publicTip.urls`. */
class StellarNetworkTipProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    override suspend fun tip(urls: List<String>): Long?
    {
        for (url in urls)
        {
            if (url.isBlank()) continue
            val health = StellarRpc.health(http, url)
            if (health != null && health.ok && health.latestLedger > 0)
            {
                return health.latestLedger
            }
            StellarRpc.latestSequence(http, url)?.let { return it }
        }
        return null
    }
}
