package rpcnode.toolkit.chains.base.infrastructure.http

import java.time.Duration
import java.time.Instant
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.nodes.infrastructure.host.SyncCatchUp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local Base base-reth-node height via `eth_blockNumber` on catalog http port, plus catch-up progress.
 *
 * reth answers `eth_syncing=false` while base-consensus feeds it blocks, so the bar is measured from the age of
 * the latest block instead (Base seals one every 2 s): share of the first seen lag already closed, see
 * [SyncCatchUp]. Needs no public tip, so it works the same on mainnet and sepolia.
 */
class BaseNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
    private val nowSeconds: () -> Long = { Instant.now().epochSecond },
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
        val block = height(nodeDir, httpPort, configFile, env) ?: return null
        val url = "http://127.0.0.1:$httpPort"
        val stamp = EthereumEthRpc.latestBlockTimestamp(http, url)
            ?: return HostNodeHeightReading(height = block)
        val lagSec = (nowSeconds() - stamp).coerceAtLeast(0).toDouble()
        if (lagSec <= SYNCED_LAG_SEC)
        {
            return HostNodeHeightReading(height = block, syncPct = 100.0, syncing = false)
        }
        val pct = SyncCatchUp.pct(SyncCatchUp.startLag(nodeDir, "sync-start-lag", lagSec), lagSec)
        return HostNodeHeightReading(height = block, syncPct = pct, syncing = true)
    }

    private companion object
    {
        /** ~15 Base blocks: head age jitters by a few seconds even on a node that is at tip. */
        const val SYNCED_LAG_SEC = 30.0
    }
}
