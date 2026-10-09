package rpcnode.toolkit.chains.hyperliquid.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Public tip = same `eth_blockNumber` call against YAML `publicTip.urls` (`…/evm`). */
class HyperliquidNetworkTipProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    override suspend fun tip(urls: List<String>): Long?
    {
        for (url in urls)
        {
            if (url.isBlank()) continue
            return EthereumEthRpc.blockNumber(http, url) ?: continue
        }
        return null
    }
}
