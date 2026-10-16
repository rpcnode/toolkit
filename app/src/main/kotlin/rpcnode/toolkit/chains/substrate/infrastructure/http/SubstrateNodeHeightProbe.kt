package rpcnode.toolkit.chains.substrate.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients
import rpcnode.toolkit.chains.nonevm.infrastructure.ReaderTipProbe

/**
 * Local height from the node's own RPC (`httpPort` = catalog role `http`).
 * During warp sync the best head stays at 0 until the state download is done — reported as syncing, never as "caught up".
 */
class SubstrateNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long? =
        reading(nodeDir, httpPort, configFile, env)?.height?.takeIf { it >= 0 }

    override suspend fun reading(nodeDir: String, httpPort: Int, configFile: String, env: String): HostNodeHeightReading?
    {
        if (httpPort <= 0)
        {
            return null
        }
        val url = "http://127.0.0.1:$httpPort"
        val height = SubstrateRpc.HEADER.read(http, url) ?: return null
        val health = SubstrateRpc.health(http, url)
        return toReading(height, health)
    }

    companion object
    {
        fun toReading(height: Long, health: SubstrateRpc.Health?): HostNodeHeightReading =
            HostNodeHeightReading(height = height, syncing = height <= 0L || health?.isSyncing == true)
    }
}

/** Public tip: `chain_getHeader` of the first `publicTip.urls` entry that answers. */
class SubstrateNetworkTipProbe(
    http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    private val delegate = ReaderTipProbe(SubstrateRpc.HEADER, http)

    override suspend fun tip(urls: List<String>): Long? = delegate.tip(urls)
}
