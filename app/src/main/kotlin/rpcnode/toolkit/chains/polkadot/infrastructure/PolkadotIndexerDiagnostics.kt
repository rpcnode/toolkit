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
 * Node-page check `polkadot_indexer` (network.yml `diagnostics`, `port: indexer`): is the optional REST API installed, is it
 * answering, and how far behind the node is its head. The env is read from the catalog port (the check runs per network).
 */
object PolkadotIndexerDiagnostics
{
    /** Finalized head of the API behind the node's best head by more than this many blocks (6 s each) is reported. */
    const val MAX_LAG_BLOCKS = 40L

    fun check(
        check: DiagnosticRequest,
        unitExists: (unit: String) -> Boolean = { Files.isRegularFile(Path.of("/etc/systemd/system", it)) },
        unitState: (unit: String) -> String = ::systemctlState,
        get: (port: Int, path: String) -> String? = ::httpGet,
        nodeHead: (rpcPort: Int) -> Long? = ::nodeBestHead,
    ): DiagnosticResult
    {
        val port = check.port.takeIf { it > 0 }
            ?: return DiagnosticResult(check.id, "unknown", "No REST API port in the catalog")
        val env = SubstrateSpecs.POLKADOT.envs.firstOrNull { SubstrateSpecs.POLKADOT.ports(it).indexer == port }
            ?: return DiagnosticResult(check.id, "unknown", "Port $port is not a Polkadot REST API port")
        val unit = PolkadotIndexer.unitName(env.id)
        if (!unitExists(unit))
        {
            return DiagnosticResult(check.id, "info", "REST API is not installed", "Install option «indexer» is none on this node.")
        }
        val health = get(port, "/v1/health")
        if (health == null)
        {
            return when (unitState(unit))
            {
                "activating" -> DiagnosticResult(
                    check.id, "info", "REST API is building or waiting for the node",
                    "The first start compiles polkadot-rest-api from source (long) and waits until the node has synced.",
                    "journalctl -u $unit -n 50 --no-pager; tail -n 30 <node_dir>/${PolkadotIndexer.LOG}",
                )
                "active" -> DiagnosticResult(check.id, "warn", "REST API process is up but not answering on $port", "It may still be reading the runtime metadata.", "tail -n 30 <node_dir>/${PolkadotIndexer.LOG}")
                else -> DiagnosticResult(check.id, "error", "REST API is not running", "Unit $unit is ${unitState(unit)}.", "journalctl -u $unit -n 50 --no-pager")
            }
        }
        val apiHead = HeightJson.long(HeightJson.root(get(port, "/v1/blocks/head/header")), listOf("number"))
        val node = nodeHead(SubstrateSpecs.POLKADOT.ports(env).rpc)
        if (apiHead == null || node == null)
        {
            return DiagnosticResult(check.id, "ok", "REST API answers on $port", "/v1/health is up; head not compared (node or API head unavailable).")
        }
        val lag = node - apiHead
        return if (lag > MAX_LAG_BLOCKS)
        {
            DiagnosticResult(check.id, "warn", "REST API head is $lag blocks behind the node", "API finalized head $apiHead, node best head $node.")
        }
        else
        {
            DiagnosticResult(check.id, "ok", "REST API is in sync", "API finalized head $apiHead, node best head $node.")
        }
    }

    private fun systemctlState(unit: String): String =
        runCatching {
            val p = ProcessBuilder("systemctl", "is-active", unit).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            out.ifEmpty { "unknown" }
        }.getOrDefault("unknown")

    private fun httpGet(port: Int, path: String): String? =
        runCatching {
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).timeout(Duration.ofSeconds(5)).GET().build()
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
