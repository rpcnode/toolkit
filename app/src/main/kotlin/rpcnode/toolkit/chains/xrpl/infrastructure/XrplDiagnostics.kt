package rpcnode.toolkit.chains.xrpl.infrastructure

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticRequest
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticResult

/** XRPL checks declared in `chains/xrpl/network.yml` → `diagnostics`. */
object XrplDiagnostics
{
    private val json = Json { ignoreUnknownKeys = true }
    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(3)).build()

    /**
     * Why the node does not get its first ledger. A healthy node has `complete_ledgers` within minutes; "connected"
     * with no ledgers after a long uptime means peers are not delivering data (timeouts: path MTU, NAT, a firewall).
     */
    fun sync(
        check: DiagnosticRequest,
        serverInfo: () -> String? = { fetchServerInfo(check.port.takeIf { it > 0 } ?: 5005) },
    ): DiagnosticResult
    {
        val raw = serverInfo()
            ?: return DiagnosticResult(check.id, "warn", "xrpld does not answer", "Admin RPC on 127.0.0.1:${check.port} is down — is the unit running?")
        val info = runCatching { json.parseToJsonElement(raw).jsonObject["result"]?.jsonObject?.get("info") as? JsonObject }
            .getOrNull()
            ?: return DiagnosticResult(check.id, "unknown", "xrpld answered without server_info")
        fun s(k: String) = info[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        val state = s("server_state").lowercase()
        val complete = s("complete_ledgers")
        val peers = info["peers"]?.jsonPrimitive?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 0L
        val uptime = info["uptime"]?.jsonPrimitive?.longOrNull ?: 0L
        val haveLedgers = complete.isNotBlank() && !complete.equals("empty", ignoreCase = true)
        val up = fmtDuration(uptime)

        if (state in setOf("full", "proposing", "validating"))
        {
            return DiagnosticResult(check.id, "ok", "In sync (state $state)", "ledgers $complete, $peers peers")
        }
        if (haveLedgers)
        {
            return DiagnosticResult(
                check.id,
                "info",
                "Syncing (state $state)",
                "Has ledgers $complete, $peers peers. History keeps filling in the background; the bar follows the ledger window.",
            )
        }
        // no ledger at all
        if (uptime < 20 * 60)
        {
            return DiagnosticResult(
                check.id,
                "info",
                "Starting up (state $state, $peers peers)",
                "The first ledgers normally arrive within 10–20 minutes (running $up).",
            )
        }
        return DiagnosticResult(
            id = check.id,
            level = if (uptime > 3 * 3600) "error" else "warn",
            title = "Not syncing: no ledgers after $up",
            detail = "State «$state» with $peers peer${if (peers == 1L) "" else "s"} and complete_ledgers empty. " +
                "Peers connect but deliver nothing — the node builds its own ledger chain and never reaches the network, " +
                "so there is no progress to show and no ETA.",
            hint = "Usual cause: large packets to peers are dropped (path MTU / NAT) — see the «Path MTU» check:\\n" +
                "echo 'net.ipv4.tcp_mtu_probing = 1' | sudo tee /etc/sysctl.d/99-rpcnode-mtu.conf && sudo sysctl --system\\n" +
                "then restart the node unit. Also check the debug log for «Ping Timeout».",
        )
    }

    private fun fetchServerInfo(port: Int): String? =
        runCatching {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"method":"server_info","params":[{}]}"""))
                .build()
            http.send(req, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() == 200 }?.body()
        }.getOrNull()

    private fun fmtDuration(sec: Long): String =
        when
        {
            sec >= 3600 -> "${"%.1f".format(Locale.ROOT, sec / 3600.0)} h"
            else -> "${sec / 60} min"
        }
}
