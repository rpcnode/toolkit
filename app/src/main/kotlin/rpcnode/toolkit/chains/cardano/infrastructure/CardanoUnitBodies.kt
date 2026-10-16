package rpcnode.toolkit.chains.cardano.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * The cardano-node unit, its command line and the generated `config.json`. Every constant here is the same value as
 * the matching `clientConfig.bindings` literal in `chains/cardano/network.yml`.
 */
object CardanoUnitBodies
{
    const val NODE_NOFILE = 1_048_576
    const val STOP_TIMEOUT_SEC = 600
    const val RTS_THREADS = 2
    const val HOST_ADDR = "0.0.0.0"
    const val METRICS_HOST = "127.0.0.1"
    const val STDOUT_FORMAT = "HumanFormatUncoloured"
    const val LEDGER_BACKEND = "V2InMemory"
    const val TARGET_ROOT = 60
    const val TARGET_KNOWN = 150
    const val TARGET_ESTABLISHED = 30
    const val TARGET_ACTIVE = 20
    const val TARGET_BIG_KNOWN = 15
    const val TARGET_BIG_ESTABLISHED = 10
    const val TARGET_BIG_ACTIVE = 5
    const val ACCEPT_HARD = 512
    const val ACCEPT_SOFT = 384
    const val ACCEPT_DELAY = 5
    const val RELATIVE_LOG = "logs/cardano.log"
    const val DB_DIR = "db"
    const val SOCKET = "node.socket"
    const val CONFIG_DIR = "config"
    const val CONFIG_JSON = "config.json"
    const val CONFIG_UPSTREAM = "config.upstream.json"
    const val TOPOLOGY_JSON = "topology.json"

    private val json = Json { prettyPrint = true }

    fun unit(env: String, nodeDir: String, socketPath: String, execStart: String, logFile: String, nofile: Int = NODE_NOFILE): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("cardano", "node.service.tmpl"),
            mapOf(
                "description" to "Cardano node ($env) — RpcNode",
                "node_dir" to nodeDir,
                "socket_path" to socketPath,
                "exec_start" to execStart,
                "log_file" to logFile,
                "stop_timeout" to STOP_TIMEOUT_SEC.toString(),
                "nofile" to nofile.coerceAtLeast(1024).toString(),
            ),
        )

    fun args(nodeDir: String, dbDir: String, socketPath: String, p2pPort: Int): List<String> =
        listOf(
            "run", "+RTS", "-N$RTS_THREADS", "-RTS",
            "--topology", "$nodeDir/$CONFIG_DIR/$TOPOLOGY_JSON",
            "--database-path", dbDir,
            "--socket-path", socketPath,
            "--host-addr", HOST_ADDR,
            "--port", p2pPort.toString(),
            "--config", "$nodeDir/$CONFIG_DIR/$CONFIG_JSON",
        )

    /** The upstream config of the installed release with the production settings applied; every other key stays. */
    fun configJson(upstream: String, metricsPort: Int): String
    {
        val root = runCatching { json.parseToJsonElement(upstream) }.getOrNull() as? JsonObject
            ?: throw IllegalArgumentException("cardano config is not a JSON object")
        val scalars = linkedMapOf(
            "TargetNumberOfRootPeers" to TARGET_ROOT,
            "TargetNumberOfKnownPeers" to TARGET_KNOWN,
            "TargetNumberOfEstablishedPeers" to TARGET_ESTABLISHED,
            "TargetNumberOfActivePeers" to TARGET_ACTIVE,
            "TargetNumberOfKnownBigLedgerPeers" to TARGET_BIG_KNOWN,
            "TargetNumberOfEstablishedBigLedgerPeers" to TARGET_BIG_ESTABLISHED,
            "TargetNumberOfActiveBigLedgerPeers" to TARGET_BIG_ACTIVE,
        )
        val out = linkedMapOf<String, JsonElement>()
        for ((key, value) in root)
        {
            out[key] = when (key)
            {
                "TraceOptions" -> patchTraceOptions(value, metricsPort)
                "LedgerDB" -> patchLedgerDb(value)
                else -> value
            }
        }
        for ((key, value) in scalars)
        {
            out[key] = JsonPrimitive(value)
        }
        out["AcceptedConnectionsLimit"] = buildJsonObject {
            put("hardLimit", ACCEPT_HARD)
            put("softLimit", ACCEPT_SOFT)
            put("delay", ACCEPT_DELAY)
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(out)) + "\n"
    }

    private fun patchTraceOptions(value: JsonElement, metricsPort: Int): JsonElement
    {
        val options = value as? JsonObject ?: return value
        val root = options[""] as? JsonObject ?: return value
        val backends = (root["backends"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content.orEmpty() } ?: emptyList()
        val patched = backends.toMutableList()
        replaceOrAppend(patched, "PrometheusSimple", "PrometheusSimple suffix $METRICS_HOST $metricsPort")
        replaceOrAppend(patched, "Stdout", "Stdout $STDOUT_FORMAT")
        val newRoot = JsonObject(root + ("backends" to JsonArray(patched.map { JsonPrimitive(it) })))
        return JsonObject(options + ("" to newRoot))
    }

    private fun replaceOrAppend(list: MutableList<String>, prefix: String, replacement: String)
    {
        val i = list.indexOfFirst { it.startsWith(prefix) }
        if (i >= 0) list[i] = replacement else list += replacement
    }

    private fun patchLedgerDb(value: JsonElement): JsonElement
    {
        val db = value as? JsonObject ?: return value
        return JsonObject(db + ("Backend" to JsonPrimitive(LEDGER_BACKEND)))
    }
}
