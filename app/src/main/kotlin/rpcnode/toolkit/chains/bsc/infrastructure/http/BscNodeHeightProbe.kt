package rpcnode.toolkit.chains.bsc.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.nodes.infrastructure.host.SyncCatchUp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local BSC geth height via `eth_blockNumber` on the catalog http port, plus catch-up progress from `eth_syncing`
 * (a node that restarts weeks behind imports chain segments at ~15 blocks/s; the bar shows the share of the first
 * seen lag already closed, see [SyncCatchUp]).
 */
class BscNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long?
    {
        if (httpPort <= 0)
        {
            return null
        }
        return EthereumEthRpc.blockNumber(http, "http://127.0.0.1:$httpPort")
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
        val url = "http://127.0.0.1:$httpPort"
        val sync = EthereumEthRpc.syncing(http, url)
        val block = EthereumEthRpc.blockNumber(http, url) ?: sync?.currentBlock ?: return null
        if (sync == null || !sync.syncing)
        {
            return HostNodeHeightReading(height = block, syncPct = null, syncing = false)
        }
        val current = sync.currentBlock ?: block
        val highest = sync.highestBlock
        val pct = if (highest != null && highest > current)
        {
            val lag = (highest - current).toDouble()
            SyncCatchUp.pct(SyncCatchUp.startLag(nodeDir, "sync-start-lag", lag), lag)
        }
        else
        {
            null
        }
        return HostNodeHeightReading(height = maxOf(block, current), syncPct = pct, syncing = true)
    }
}
