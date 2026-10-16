package rpcnode.toolkit.chains.polkadot.infrastructure

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import rpcnode.toolkit.chains.nonevm.infrastructure.HeightJson
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateSpecs
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticRequest
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticResult

/**
 * Node-page check `polkadot_history` (network.yml `diagnostics`, `port: history_api`): is the history indexer installed and
 * prepared, are the processor and the GraphQL API running, and how far behind the node is the height the processor has
 * stored (`squidStatus` of the API, i.e. `squid_processor.status`). A first backfill is a long, expected lag, so a large lag
 * is information as long as the processor runs; only a stopped unit is an error.
 */
object PolkadotHistoryDiagnostics
{
    /** Stored height behind the node's best head by at most this many blocks (6 s each) counts as in sync. */
    const val MAX_LAG_BLOCKS = 40L

    const val STATUS_QUERY = """{"query":"{ squidStatus { height finalizedHeight } }"}"""

    fun check(
        check: DiagnosticRequest,
        unitExists: (unit: String) -> Boolean = { Files.isRegularFile(Path.of("/etc/systemd/system", it)) },
        unitState: (unit: String) -> String = ::systemctlState,
        status: (port: Int) -> String? = ::postStatus,
        nodeHead: (rpcPort: Int) -> Long? = ::nodeBestHead,
    ): DiagnosticResult
    {
        val port = check.port.takeIf { it > 0 }
            ?: return DiagnosticResult(check.id, "unknown", "No history API port in the catalog")
        val env = SubstrateSpecs.POLKADOT.envs.firstOrNull { SubstrateSpecs.POLKADOT.ports(it).historyApi == port }
            ?: return DiagnosticResult(check.id, "unknown", "Port $port is not a Polkadot history API port")
        val api = PolkadotHistory.apiUnitName(env.id)
        val processor = PolkadotHistory.unitName(env.id)
        val prepare = PolkadotHistory.prepareUnitName(env.id)
        if (!unitExists(api))
        {
            return DiagnosticResult(check.id, "info", "History indexer is not installed", "Install option «indexer» is not history on this node.")
        }
        val prepareState = unitState(prepare)
        val readyHint = "tail -n 30 <node_dir>/${PolkadotHistory.PREPARE_LOG}; journalctl -u $prepare -n 50 --no-pager"
        if (prepareState == "failed")
        {
            return DiagnosticResult(check.id, "error", "History indexer preparation failed", "Unit $prepare is failed.", readyHint)
        }
        if (prepareState != "active")
        {
            return DiagnosticResult(
                check.id, "info", "History indexer is being prepared",
                "PostgreSQL role/database, Node.js ${PolkadotHistory.NODE_VERSION} and the npm build run on the first Start (a few minutes); the processor and the API wait for them.",
                readyHint,
            )
        }
        val processorState = unitState(processor)
        val body = status(port)
        val stored = body?.let { height(it) }
        if (body == null)
        {
            val apiState = unitState(api)
            return if (apiState == "active")
            {
                DiagnosticResult(check.id, "warn", "History API process is up but not answering on $port", "It may still be starting.", "tail -n 30 <node_dir>/${PolkadotHistory.API_LOG}")
            }
            else
            {
                DiagnosticResult(check.id, "error", "History API is not running", "Unit $api is $apiState.", "journalctl -u $api -n 50 --no-pager")
            }
        }
        if (processorState != "active" && processorState != "activating")
        {
            return DiagnosticResult(
                check.id, "error", "History processor is not running",
                "Unit $processor is $processorState${stored?.takeIf { it >= 0 }?.let { "; stored height $it" }.orEmpty()}.",
                "journalctl -u $processor -n 50 --no-pager; tail -n 30 <node_dir>/${PolkadotHistory.PROCESSOR_LOG}",
            )
        }
        if (stored == null || stored < 0)
        {
            return DiagnosticResult(
                check.id, "info", "History processor has not stored a block yet",
                "It waits until the node is synced, then reads blocks from the node's RPC (a first run starts at the configured start block).",
                "tail -n 30 <node_dir>/${PolkadotHistory.PROCESSOR_LOG}",
            )
        }
        val node = nodeHead(SubstrateSpecs.POLKADOT.ports(env).rpc)
            ?: return DiagnosticResult(check.id, "ok", "History indexer answers on $port", "Stored height $stored; the node head is not available for a lag check.")
        val lag = node - stored
        return if (lag <= MAX_LAG_BLOCKS)
        {
            DiagnosticResult(check.id, "ok", "History indexer is in sync", "Stored height $stored, node best head $node (lag $lag).")
        }
        else
        {
            val pct = if (node > 0) (stored * 1000 / node) / 10.0 else 0.0
            DiagnosticResult(
                check.id, "info", "History indexer is catching up: $lag blocks behind the node",
                "Stored height $stored of node best head $node (${pct}% of the chain counted from block 0; a start block above 0 skips the earlier part). A backfill runs as fast as the node's RPC answers.",
                "tail -n 5 <node_dir>/${PolkadotHistory.PROCESSOR_LOG}",
            )
        }
    }

    /** `data.squidStatus.height` of a GraphQL answer (-1 while nothing is stored), or null when it is not one. */
    fun height(body: String): Long? =
        HeightJson.long(HeightJson.root(body), listOf("data", "squidStatus", "height"))

    private fun systemctlState(unit: String): String =
        runCatching {
            val p = ProcessBuilder("systemctl", "is-active", unit).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            out.ifEmpty { "unknown" }
        }.getOrDefault("unknown")

    private fun postStatus(port: Int): String? =
        runCatching {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/graphql")).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(STATUS_QUERY)).build()
            val resp = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()
                .send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299) resp.body() else null
        }.getOrNull()

    private fun nodeBestHead(rpcPort: Int): Long? =
        runCatching {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$rpcPort/")).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"chain_getHeader","params":[]}""")).build()
            val resp = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()
                .send(req, HttpResponse.BodyHandlers.ofString())
            HeightJson.long(HeightJson.root(resp.body()), listOf("result", "number"))
        }.getOrNull()
}
