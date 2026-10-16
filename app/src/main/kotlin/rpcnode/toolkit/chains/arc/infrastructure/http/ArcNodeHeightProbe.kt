package rpcnode.toolkit.chains.arc.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local Arc height via `eth_blockNumber` on the catalog http port (null while the snapshot is still being restored).
 * Sync progress comes from the consensus layer's `SYNC REQUIRED … peer_height=N` log line in `logs/arc.log`:
 * Reth never reports `eth_syncing` (blocks arrive from the CL), so the log is the only live lag signal.
 */
class ArcNodeHeightProbe(
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
        val block = if (httpPort > 0) ArcRpc.blockNumber(http, "http://127.0.0.1:$httpPort") else null
        val peer = ArcSyncLog.peerHeight(tailLog(nodeDir))
        if (block == null)
        {
            return peer?.let { HostNodeHeightReading(height = -1, syncPct = null, syncing = true) }
        }
        if (peer == null || peer <= 0 || peer - block <= SYNCED_LAG)
        {
            return HostNodeHeightReading(height = block, syncPct = if (peer != null) 100.0 else null, syncing = false)
        }
        return HostNodeHeightReading(
            height = block,
            syncPct = (block.toDouble() / peer * 100.0).coerceIn(0.0, 99.9),
            syncing = true,
        )
    }

    private fun tailLog(nodeDir: String): String?
    {
        val path = Path.of(nodeDir.trim(), "logs", "arc.log")
        if (!Files.isRegularFile(path))
        {
            return null
        }
        return try
        {
            Files.newByteChannel(path).use { ch ->
                val take = minOf(ch.size(), TAIL_BYTES.toLong()).toInt()
                ch.position(ch.size() - take)
                val buf = java.nio.ByteBuffer.allocate(take)
                while (buf.hasRemaining() && ch.read(buf) > 0) { }
                String(buf.array(), 0, buf.position())
            }
        }
        catch (_: Exception)
        {
            null
        }
    }

    private companion object
    {
        const val TAIL_BYTES = 256 * 1024

        /** Consensus is a block or two behind the peer tip on a healthy node. */
        const val SYNCED_LAG = 3L
    }
}

/** Parses the consensus-layer sync line out of the (ANSI-coloured) node log. */
object ArcSyncLog
{
    private val line = Regex("""SYNC REQUIRED.*?peer_height=(\d+)""")

    fun peerHeight(log: String?): Long?
    {
        if (log.isNullOrEmpty())
        {
            return null
        }
        return line.findAll(log).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
    }
}
