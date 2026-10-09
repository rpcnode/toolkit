package rpcnode.toolkit.chains.solana.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local Agave height via `getSlot`, plus cluster archive download % from
 * `logs/validator.log` (`solana_file_download` lines) while RPC is still down.
 */
class SolanaNodeHeightProbe(
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
        val download = SolanaDownloadLog.parse(tailValidatorLog(nodeDir))
        val slot = if (httpPort > 0)
        {
            SolanaRpc.getSlot(http, "http://127.0.0.1:$httpPort")
        }
        else
        {
            null
        }
        if (slot == null && download == null)
        {
            return null
        }
        if (slot == null)
        {
            // Archive/genesis download before RPC answers — still push progress to the panel.
            return HostNodeHeightReading(
                height = -1,
                syncPct = download!!.pct,
                syncing = download.pct < 99.9,
            )
        }
        if (download != null && download.pct < 99.9)
        {
            return HostNodeHeightReading(
                height = slot,
                syncPct = download.pct,
                syncing = true,
            )
        }
        return HostNodeHeightReading(height = slot, syncPct = null, syncing = false)
    }

    private fun tailValidatorLog(nodeDir: String): String?
    {
        val root = nodeDir.trim()
        if (root.isEmpty())
        {
            return null
        }
        val candidates = listOf(
            Path.of(root, "logs", "validator.log"),
            Path.of(root, "logs", "solana-testnet.log"),
            Path.of(root, "logs", "solana-mainnet.log"),
            Path.of(root, "logs", "solana-devnet.log"),
            Path.of(root, "logs", "node.out"),
        )
        val path = candidates.firstOrNull { Files.isRegularFile(it) } ?: return null
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
}
