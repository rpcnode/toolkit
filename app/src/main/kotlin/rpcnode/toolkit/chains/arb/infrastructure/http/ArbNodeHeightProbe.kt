package rpcnode.toolkit.chains.arb.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.nodes.infrastructure.host.SyncCatchUp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local Nitro height via `eth_blockNumber`, plus init-database download % from
 * `logs/node.out` (`transferred N / M bytes (p%)`) while JSON-RPC is still down.
 *
 * Nitro answers `eth_syncing=false` even while it is far behind (it waits for batches from the parent chain), so
 * "not syncing" is not trusted: the bar is the share of the first seen lag (age of the latest block) already closed,
 * see [SyncCatchUp].
 */
class ArbNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
    private val nowSeconds: () -> Long = { Instant.now().epochSecond },
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
        val download = ArbNitroDownloadLog.parse(tailLog(nodeDir))
        val url = if (httpPort > 0) "http://127.0.0.1:$httpPort" else null
        val block = if (url != null) EthereumEthRpc.blockNumber(http, url) else null
        val sync = if (url != null) EthereumEthRpc.syncing(http, url) else null
        if (block == null)
        {
            if (download != null)
            {
                // Init download / extract before RPC answers — still push progress to the panel.
                return HostNodeHeightReading(
                    height = -1,
                    syncPct = download.pct,
                    syncing = download.pct < 99.9,
                )
            }
            return null
        }
        if (download != null && download.pct < 99.9)
        {
            return HostNodeHeightReading(
                height = block,
                syncPct = download.pct,
                syncing = true,
            )
        }
        if (sync == null || !sync.syncing)
        {
            return headLagReading(nodeDir, url, block)
        }
        val height = listOfNotNull(block, sync.currentBlock).maxOrNull() ?: block
        return HostNodeHeightReading(
            height = height,
            syncPct = sync.blockPct?.coerceIn(0.0, 99.9),
            syncing = true,
        )
    }

    private suspend fun headLagReading(nodeDir: String, url: String?, block: Long): HostNodeHeightReading
    {
        val stamp = url?.let { EthereumEthRpc.latestBlockTimestamp(http, it) }
            ?: return HostNodeHeightReading(height = block, syncPct = null, syncing = false)
        val lagSec = (nowSeconds() - stamp).coerceAtLeast(0).toDouble()
        if (lagSec <= SYNCED_LAG_SEC)
        {
            return HostNodeHeightReading(height = block, syncPct = 100.0, syncing = false)
        }
        val pct = SyncCatchUp.pct(SyncCatchUp.startLag(nodeDir, "sync-start-lag", lagSec), lagSec)
        return HostNodeHeightReading(height = block, syncPct = pct, syncing = true)
    }

    private fun tailLog(nodeDir: String): String?
    {
        val path = Path.of(nodeDir.trim(), "logs", "node.out")
        if (!Files.isRegularFile(path))
        {
            return null
        }
        return try
        {
            val bytes = Files.readAllBytes(path)
            val take = minOf(bytes.size, 512 * 1024)
            String(bytes, bytes.size - take, take)
        }
        catch (_: Exception)
        {
            null
        }
    }

    private companion object
    {
        /** Nitro only seals a block when there is a transaction, so a quiet testnet legitimately idles for minutes. */
        const val SYNCED_LAG_SEC = 15 * 60.0
    }
}
