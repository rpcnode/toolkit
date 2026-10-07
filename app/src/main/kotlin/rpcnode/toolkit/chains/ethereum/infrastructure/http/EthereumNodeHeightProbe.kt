package rpcnode.toolkit.chains.ethereum.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local geth height + snap/IBD progress via `eth_syncing` and `logs/node.out`
 * (`synced=…%` state/chain lines). Not a toolkit snapshot download.
 */
class EthereumNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long?
    {
        return reading(nodeDir, httpPort, configFile, env)?.height
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
        val block = EthereumEthRpc.blockNumber(http, url)
        val sync = EthereumEthRpc.syncing(http, url)
        val logPct = EthereumEthRpc.parseSnapSyncPctFromLog(tailLog(nodeDir))
        val height = listOfNotNull(block, sync?.currentBlock).maxOrNull() ?: return null
        if (sync == null)
        {
            return HostNodeHeightReading(height = height, syncPct = logPct, syncing = logPct != null && logPct < 100.0)
        }
        if (!sync.syncing)
        {
            return HostNodeHeightReading(height = height, syncPct = 100.0, syncing = false)
        }
        val candidates = listOfNotNull(sync.blockPct, logPct)
        val pct = when
        {
            candidates.isEmpty() -> null
            else -> candidates.min().coerceIn(0.0, 99.9)
        }
        return HostNodeHeightReading(height = height, syncPct = pct, syncing = true)
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
            val take = minOf(bytes.size, 256 * 1024)
            String(bytes, bytes.size - take, take)
        }
        catch (_: Exception)
        {
            null
        }
    }
}
