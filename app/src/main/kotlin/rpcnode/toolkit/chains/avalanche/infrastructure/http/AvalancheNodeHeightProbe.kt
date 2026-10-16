package rpcnode.toolkit.chains.avalanche.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local C-Chain height (`eth_blockNumber` on the HTTP port). While the node is still bootstrapping
 * (`info.isBootstrapped` false, or the C-Chain RPC not up yet) it is reported as syncing.
 */
class AvalancheNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long?
    {
        return reading(nodeDir, httpPort, configFile, env)?.height?.takeIf { it >= 0 }
    }

    override suspend fun reading(
        nodeDir: String,
        httpPort: Int,
        configFile: String,
        env: String,
    ): HostNodeHeightReading?
    {
        if (httpPort <= 0)
        {
            return null
        }
        val base = "http://127.0.0.1:$httpPort"
        val bootstrapped = AvalancheRpc.isBootstrapped(http, base) ?: return null
        val height = AvalancheRpc.blockNumber(http, base)
        if (!bootstrapped)
        {
            return HostNodeHeightReading(height = height ?: -1, syncPct = null, syncing = true)
        }
        return height?.let { HostNodeHeightReading(height = it, syncPct = null, syncing = false) }
    }
}
