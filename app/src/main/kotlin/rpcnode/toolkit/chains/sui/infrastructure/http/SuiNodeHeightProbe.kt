package rpcnode.toolkit.chains.sui.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import rpcnode.toolkit.chains.sui.infrastructure.SuiPortTable
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local Sui height via Prometheus `highest_synced_checkpoint`, falling back to
 * JSON-RPC `sui_getLatestCheckpointSequenceNumber`. While formal snapshot runs,
 * mirrors progress from `.snapshot-state.json` / log.
 */
class SuiNodeHeightProbe(
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
        val ports = SuiPortTable.forEnv(env)
        val metricsPort = ports.metrics
        val fromMetrics = SuiRpc.scrapeSyncedCheckpoint(http, metricsPort)
        val fromRpc = if (httpPort > 0)
        {
            SuiRpc.latestCheckpoint(http, "http://127.0.0.1:$httpPort")
        }
        else
        {
            null
        }
        val checkpoint = maxOfNullable(fromMetrics, fromRpc)
        val snap = SuiFormalSnapshotProgress.read(nodeDir)
        if (checkpoint == null && snap == null)
        {
            return null
        }
        if (checkpoint == null)
        {
            return HostNodeHeightReading(
                height = -1,
                syncPct = snap!!.pct,
                syncing = snap.pct < 99.9,
            )
        }
        if (snap != null && snap.pct < 99.9 && !hasFormalMarker(nodeDir))
        {
            return HostNodeHeightReading(
                height = checkpoint,
                syncPct = snap.pct,
                syncing = true,
            )
        }
        // Checkpoint 0 without formal marker — genesis stall / tip-dead, not "synced".
        if (checkpoint == 0L && !hasFormalMarker(nodeDir))
        {
            return HostNodeHeightReading(height = 0, syncPct = null, syncing = true)
        }
        return HostNodeHeightReading(height = checkpoint, syncPct = null, syncing = false)
    }

    private fun hasFormalMarker(nodeDir: String): Boolean
    {
        val root = Path.of(nodeDir.trim())
        return listOf(
            root.resolve(".snapshot-ready"),
            root.resolve("db").resolve(".snapshot-ready"),
        ).any { Files.isRegularFile(it) }
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long?
    {
        if (a == null) return b
        if (b == null) return a
        return maxOf(a, b)
    }
}
