package rpcnode.toolkit.chains.optimism.infrastructure.http

import java.nio.ByteBuffer
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
 * Local height via `eth_blockNumber` on the op-reth http port (null while the node is building or restoring its snapshot).
 *
 * Sync progress, in order:
 *  1. op-reth's pipeline (`Status … stage=Execution … target=N` + `Executed block range … end=M` in `logs/optimism.log`):
 *     op-node keeps handing it fresh unsafe payloads the whole time, so the RPC head and `eth_syncing` say nothing about
 *     the historical catch-up — the log is the only live signal. See [OptimismSyncLog].
 *  2. once the pipeline is done, the age of the latest block (share of the first seen lag already closed, [SyncCatchUp]).
 */
class OptimismNodeHeightProbe(
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
        return OptimismRpc.blockNumber(http, "http://127.0.0.1:$httpPort")
    }

    override suspend fun reading(
        nodeDir: String,
        httpPort: Int,
        configFile: String,
        env: String,
    ): HostNodeHeightReading?
    {
        val block = height(nodeDir, httpPort, configFile, env)
        val pipeline = OptimismSyncLog.pipelinePct(tailLog(nodeDir))
        if (pipeline != null)
        {
            return HostNodeHeightReading(height = block ?: -1, syncPct = pipeline, syncing = true)
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

    private fun tailLog(nodeDir: String): String?
    {
        val path = Path.of(nodeDir.trim(), "logs", "optimism.log")
        if (!Files.isRegularFile(path))
        {
            return null
        }
        return try
        {
            Files.newByteChannel(path).use { ch ->
                val take = minOf(ch.size(), TAIL_BYTES.toLong()).toInt()
                ch.position(ch.size() - take)
                val buf = ByteBuffer.allocate(take)
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
        /** op-node logs ~15 KB/s next to op-reth's 25 s `Status` line: a megabyte always holds the last one. */
        const val TAIL_BYTES = 1024 * 1024

        /** ~15 OP blocks (2 s each): head age jitters by a few seconds even on a node that is at tip. */
        const val SYNCED_LAG_SEC = 30.0
    }
}

/** Parses op-reth's pipeline progress out of the (ANSI-coloured) node log shared with op-node. */
object OptimismSyncLog
{
    private val ansi = Regex("""\u001B?\[[0-9;]*m""")
    private val statusLine = Regex("""\bStatus\b[^\n]*\bconnected_peers=[^\n]*""")
    private val stageName = Regex("""\bstage=(\w+)""")
    private val target = Regex("""\btarget=(\d+)""")
    private val checkpoint = Regex("""\bcheckpoint=(\d+)""")
    private val stageProgress = Regex("""\bstage_progress=([0-9.]+)%""")
    private val executed = Regex("""Executed block range[^\n]*\bend=(\d+)""")

    /**
     * 0..99.9 while the last op-reth `Status` line belongs to a pipeline stage; null when the pipeline is not running
     * (live sync lines carry no `stage=`) or the log has no usable line.
     *
     * Execution is the long stage, so its bar is the live executed block over the pipeline target (the line's own
     * `checkpoint` only moves on commit); the other stages report their own `stage_progress`.
     */
    fun pipelinePct(log: String?): Double?
    {
        if (log.isNullOrEmpty())
        {
            return null
        }
        val text = ansi.replace(log, "")
        val line = statusLine.findAll(text).lastOrNull()?.value ?: return null
        val stage = stageName.find(line)?.groupValues?.get(1) ?: return null
        val tgt = target.find(line)?.groupValues?.get(1)?.toLongOrNull()
        if (stage == "Execution" && tgt != null && tgt > 0)
        {
            val end = executed.findAll(text).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
                ?: checkpoint.find(line)?.groupValues?.get(1)?.toLongOrNull()
                ?: 0L
            return (end.toDouble() / tgt * 100.0).coerceIn(0.0, 99.9)
        }
        return stageProgress.find(line)?.groupValues?.get(1)?.toDoubleOrNull()?.coerceIn(0.0, 99.9)
    }
}
