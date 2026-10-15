package rpcnode.toolkit.chains.polygon.infrastructure.http

import java.time.Duration
import java.time.Instant
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc
import rpcnode.toolkit.chains.polygon.infrastructure.PolygonPortTable
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.nodes.infrastructure.host.SyncCatchUp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local bor height via `eth_blockNumber` on catalog http port, plus catch-up progress in two phases.
 *
 * 1. Heimdall first: bor refuses to follow the chain ("Heimdall isn't synced, waiting for update") until Heimdall
 *    reports `catching_up=false`, so while it catches up the bar is the share of Heimdall's first seen lag (age of its
 *    latest block) already closed, see [SyncCatchUp].
 * 2. Then bor itself, measured from the age of its latest block (a block every 2 s).
 *
 * Neither needs a public tip, so it works the same on mainnet and amoy.
 */
class PolygonNodeHeightProbe(
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
        return PolygonBorRpc.blockNumber(http, "http://127.0.0.1:$httpPort")
    }

    override suspend fun reading(
        nodeDir: String,
        httpPort: Int,
        configFile: String,
        env: String,
    ): HostNodeHeightReading?
    {
        val block = height(nodeDir, httpPort, configFile, env)
        val heimdall = PolygonHeimdallStatus.fetch(http, "http://127.0.0.1:${PolygonPortTable.forEnv(env).heimdallRpc}")
        if (heimdall != null && heimdall.catchingUp)
        {
            val lag = (nowSeconds() - heimdall.latestBlockTime).coerceAtLeast(0).toDouble()
            val pct = SyncCatchUp.pct(SyncCatchUp.startLag(nodeDir, "heimdall-start-lag", lag), lag)
            return HostNodeHeightReading(height = block ?: -1, syncPct = pct, syncing = true)
        }
        if (block == null)
        {
            return null
        }
        val stamp = EthereumEthRpc.latestBlockTimestamp(http, "http://127.0.0.1:$httpPort")
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
        /** ~15 Polygon blocks: head age jitters by a few seconds even on a node that is at tip. */
        const val SYNCED_LAG_SEC = 30.0
    }
}
