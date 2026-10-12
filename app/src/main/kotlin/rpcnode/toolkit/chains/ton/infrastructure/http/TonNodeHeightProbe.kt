package rpcnode.toolkit.chains.ton.infrastructure.http

import rpcnode.toolkit.nodes.infrastructure.host.LogTail
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import rpcnode.toolkit.chains.ton.infrastructure.TonBootstrapProgress
import rpcnode.toolkit.chains.ton.infrastructure.TonUnitBodies
import rpcnode.toolkit.chains.ton.infrastructure.TonValidatorLag
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local TON height via TON HTTP API `getMasterchainInfo` → masterchain seqno.
 */
class TonNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(4))),
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
        val seq = TonGetMasterchainInfo.seqno(http, "http://127.0.0.1:$httpPort")
            ?: return bootstrapReading(nodeDir)
        // Seqno 0 right after bootstrap is not a healthy tip yet.
        return HostNodeHeightReading(
            height = seq,
            syncPct = null,
            syncing = seq == 0L,
        )
    }

    /**
     * The HTTP API is down while MyTonCtrl is still being installed (hours on a fresh host): report the bootstrap
     * progress so the panel shows a bar instead of a silent "starting".
     */
    private fun bootstrapReading(nodeDir: String): HostNodeHeightReading?
    {
        val root = Path.of(nodeDir)
        if (Files.exists(root.resolve(TonUnitBodies.BOOTSTRAP_DONE)))
        {
            return catchUpReading(root)
        }
        val log = root.resolve("logs").resolve("bootstrap.log")
        val pct = runCatching { TonBootstrapProgress.parse(readTail(log)) { unpackedDbBytes() } }.getOrNull() ?: return null
        return HostNodeHeightReading(height = -1, syncPct = pct, syncing = true)
    }

    /**
     * Installed, API still 503: the validator is catching up from the dump. Progress = share of the lag closed since
     * the first lag we saw (kept in `.toolkit/ton-catchup-start-lag`), so the bar moves honestly instead of keeping
     * the stale bootstrap percentage.
     */
    private fun catchUpReading(root: Path): HostNodeHeightReading?
    {
        val now = runCatching { TonValidatorLag.lastLagSeconds(validatorLogTail()) }.getOrNull() ?: return null
        val startFile = root.resolve(".toolkit").resolve("ton-catchup-start-lag")
        val start = runCatching { Files.readString(startFile).trim().toDouble() }.getOrNull()
            ?.takeIf { it >= now }
            ?: now.also { runCatching { Files.writeString(startFile, "$it\n") } }
        return HostNodeHeightReading(height = -1, syncPct = TonValidatorLag.catchUpPct(start, now), syncing = true)
    }

    /**
     * Bytes the dump extraction has written so far: the apparent size of everything under `/var/ton-work/db` (tens of
     * thousands of files, a fraction of a second). Called only while the log says the extraction is running.
     */
    private fun unpackedDbBytes(): Long?
    {
        val db = Path.of("/var/ton-work/db")
        if (!Files.isDirectory(db))
        {
            return null
        }
        val deadline = System.nanoTime() + 5_000_000_000L
        var total = 0L
        return runCatching {
            Files.walk(db).use { stream ->
                val it = stream.iterator()
                while (it.hasNext())
                {
                    if (System.nanoTime() > deadline)
                    {
                        break
                    }
                    val p = it.next()
                    if (Files.isRegularFile(p))
                    {
                        total += Files.size(p)
                    }
                }
            }
            total
        }.getOrNull()
    }

    /** Newest `log*.log` under /var/ton-work (validator-engine writes per-thread logs there). */
    private fun validatorLogTail(): String
    {
        val work = Path.of("/var/ton-work")
        if (!Files.isDirectory(work))
        {
            return ""
        }
        val newest = Files.list(work).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().let { n -> n.startsWith("log") && n.endsWith(".log") } }
                .max(compareBy { Files.getLastModifiedTime(it) })
                .orElse(null)
        } ?: return ""
        return readTail(newest, 256 * 1024)
    }

    private fun readTail(file: Path, bytes: Int = 512 * 1024): String = LogTail.read(file, bytes).orEmpty()
}
