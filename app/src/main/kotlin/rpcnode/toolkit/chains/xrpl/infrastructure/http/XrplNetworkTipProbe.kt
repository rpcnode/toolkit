package rpcnode.toolkit.chains.xrpl.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Public tip = `server_info` validated_ledger.seq against YAML `publicTip.urls`. */
class XrplNetworkTipProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    override suspend fun tip(urls: List<String>): Long?
    {
        for (url in urls)
        {
            if (url.isBlank()) continue
            val info = XrplRpc.serverInfo(http, url) ?: continue
            if (info.ok && info.seq > 0)
            {
                return info.seq
            }
        }
        return null
    }
}
