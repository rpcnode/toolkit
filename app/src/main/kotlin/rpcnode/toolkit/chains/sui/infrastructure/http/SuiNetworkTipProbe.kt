package rpcnode.toolkit.chains.sui.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.chains.sui.infrastructure.SuiClusters
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Public tip = `sui_getLatestCheckpointSequenceNumber` against YAML `publicTip.urls`,
 * then GraphQL checkpoint fallback (Mysten fullnode JSON-RPC is often deprecated).
 */
class SuiNetworkTipProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    override suspend fun tip(urls: List<String>): Long?
    {
        for (url in urls)
        {
            if (url.isBlank()) continue
            val n = SuiRpc.latestCheckpoint(http, url) ?: continue
            if (n > 0)
            {
                return n
            }
        }
        val envHint = urls.firstOrNull()?.lowercase().orEmpty()
        val cluster = when
        {
            envHint.contains("testnet") -> SuiClusters.lookup("testnet")
            else -> SuiClusters.lookup("mainnet")
        }
        val override = System.getenv("SUI_PUBLIC_TIP_GRAPHQL")?.trim().orEmpty()
        val gql = override.ifEmpty { cluster.graphQlTipUrl }
        return SuiRpc.graphQlCheckpoint(http, gql)?.takeIf { it > 0 }
    }
}
