package rpcnode.toolkit.chains.cardano.infrastructure

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.chains.cardano.infrastructure.http.CardanoDbSyncMetrics
import rpcnode.toolkit.chains.cardano.infrastructure.http.CardanoOgmiosMetrics
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticRequest
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticResult

/**
 * Cardano indexer checks declared in `chains/cardano/network.yml` → `diagnostics` (`cardano_ogmios`, `cardano_dbsync`).
 * `ok` is hidden by the node page, so a healthy or disabled companion shows nothing; syncing shows as `info` with the
 * percentage, a dead one as `warn`. The node's own height stays the Prometheus probe (CardanoNodeHeightProbe).
 */
object CardanoDiagnostics
{
    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3)).build()

    fun ogmios(
        check: DiagnosticRequest,
        mode: () -> CardanoIndexerMode = { readMode(check) },
        scrape: (port: Int) -> String? = ::fetchMetrics,
    ): DiagnosticResult
    {
        if (!mode().ogmios)
        {
            return DiagnosticResult(check.id, "ok", "Ogmios is not enabled")
        }
        val port = check.port.takeIf { it > 0 }
            ?: return DiagnosticResult(check.id, "unknown", "No Ogmios port in the catalog")
        val unit = CardanoIndexerBodies.ogmiosUnitName(check.params["env"].orEmpty())
        val m = CardanoOgmiosMetrics.parse(scrape(port))
            ?: return DiagnosticResult(
                check.id,
                "warn",
                "Ogmios does not answer",
                "Nothing on 127.0.0.1:$port — still starting, or the unit is failing (Ogmios exits while the node socket is missing).",
                "systemctl status $unit --no-pager; tail -n 50 ${logPath(check, CardanoIndexerBodies.OGMIOS_LOG)}",
            )
        if (!m.connected)
        {
            return DiagnosticResult(
                check.id,
                "warn",
                "Ogmios is not connected to the node",
                "Ogmios answers on 127.0.0.1:$port but has no link to the node socket (node stopped, restoring a snapshot, or still replaying its DB).",
            )
        }
        val sync = m.sync
        if (sync != null && sync < CardanoOgmiosMetrics.AT_TIP)
        {
            return DiagnosticResult(
                check.id,
                "info",
                "Ogmios follows a syncing node (${pct(sync * 100)})",
                "Queries are answered from the node's current state, which is still behind the network" +
                    (m.tipBlock?.let { " (tip block $it)" } ?: "") + ".",
            )
        }
        return DiagnosticResult(
            check.id,
            "ok",
            "Ogmios is connected",
            (m.tipBlock?.let { "tip block $it, " } ?: "") + "${m.activeConnections ?: 0} WebSocket connection(s)",
        )
    }

    fun dbSync(
        check: DiagnosticRequest,
        mode: () -> CardanoIndexerMode = { readMode(check) },
        scrape: (port: Int) -> String? = ::fetchMetrics,
        ready: () -> Boolean = { readyMarker(check) },
        nowUnixSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    ): DiagnosticResult
    {
        if (!mode().dbSync)
        {
            return DiagnosticResult(check.id, "ok", "cardano-db-sync is not enabled")
        }
        val port = check.port.takeIf { it > 0 }
            ?: return DiagnosticResult(check.id, "unknown", "No db-sync metrics port in the catalog")
        val env = check.params["env"].orEmpty()
        val m = CardanoDbSyncMetrics.parse(scrape(port))
        if (m == null)
        {
            if (!ready())
            {
                return DiagnosticResult(
                    check.id,
                    "info",
                    "db-sync is waiting for PostgreSQL",
                    "The preparation unit has not finished yet: it creates the role, database and tablespace (and installs PostgreSQL when the host has none).",
                    "systemctl status ${CardanoIndexerBodies.prepareUnitName(env)} --no-pager; tail -n 50 ${logPath(check, CardanoIndexerBodies.PREPARE_LOG)}",
                )
            }
            return DiagnosticResult(
                check.id,
                "warn",
                "db-sync metrics do not answer",
                "Nothing on 127.0.0.1:$port — db-sync waits for the node socket, or it is failing.",
                "systemctl status ${CardanoIndexerBodies.dbSyncUnitName(env)} --no-pager; tail -n 50 ${logPath(check, CardanoIndexerBodies.DBSYNC_LOG)}",
            )
        }
        if (m.dbBlock == 0L && m.dbSlot == 0L)
        {
            return DiagnosticResult(
                check.id,
                "info",
                "db-sync is starting",
                "Schema migrations and the ledger state are loading; no block is in the database yet.",
            )
        }
        val progress = CardanoDbSyncMetrics.progress(m, CardanoClusters.lookup(env), nowUnixSeconds())
        if (progress.atTip)
        {
            return DiagnosticResult(check.id, "ok", "db-sync is at the tip", "database block ${m.dbBlock}, epoch ${m.epoch ?: "?"}")
        }
        val of = m.nodeBlock?.takeIf { it > 0 }?.let { " of ${it}" }.orEmpty()
        return DiagnosticResult(
            check.id,
            "info",
            "db-sync is syncing: ${pct(progress.pct)}",
            "Database at block ${m.dbBlock}$of, ${behind(progress.lagSlots)} behind the wall clock" +
                (m.queue?.let { ", $it events queued" } ?: "") +
                ". A full mainnet sync takes days; the node itself is not affected.",
        )
    }

    /** Slots are seconds on the Shelley clock. */
    private fun behind(seconds: Long): String =
        when
        {
            seconds >= 2 * 86_400 -> "${seconds / 86_400} days"
            seconds >= 2 * 3_600 -> "${seconds / 3_600} hours"
            else -> "${seconds / 60} min"
        }

    private fun pct(v: Double) = String.format(Locale.ROOT, "%.1f%%", v)

    private fun nodeDir(check: DiagnosticRequest): Path? =
        check.params["node_dir"]?.trim()?.takeIf { it.startsWith("/") && !it.contains("..") }?.let { Path.of(it) }

    private fun logPath(check: DiagnosticRequest, relative: String): String =
        nodeDir(check)?.resolve(relative)?.toString() ?: relative

    /** `.toolkit/cardano-indexer.json` written by the starter; no file = a node that never had an indexer. */
    fun readMode(check: DiagnosticRequest): CardanoIndexerMode
    {
        val file = nodeDir(check)?.resolve(CardanoIndexerBodies.STATE_FILE) ?: return CardanoIndexerMode.NONE
        return runCatching {
            CardanoIndexerMode.parse(json.parseToJsonElement(Files.readString(file)).jsonObject["mode"]?.jsonPrimitive?.contentOrNull)
        }.getOrDefault(CardanoIndexerMode.NONE)
    }

    private fun readyMarker(check: DiagnosticRequest): Boolean =
        nodeDir(check)?.resolve(CardanoIndexerBodies.READY_MARKER)?.let { Files.isRegularFile(it) } ?: false

    private fun fetchMetrics(port: Int): String? =
        runCatching {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/metrics")).timeout(Duration.ofSeconds(4)).GET().build()
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            res.body().takeIf { res.statusCode() == 200 }
        }.getOrNull()
}
