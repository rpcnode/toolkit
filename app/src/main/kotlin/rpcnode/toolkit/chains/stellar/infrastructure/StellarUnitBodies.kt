package rpcnode.toolkit.chains.stellar.infrastructure

import java.nio.file.Path
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * stellar-rpc.toml, the heal script and the systemd unit. Every constant here is the same value as the matching
 * `clientConfig.bindings` literal in `chains/stellar/network.yml`.
 */
object StellarUnitBodies
{
    const val NODE_NOFILE = 1_048_576
    const val ENDPOINT_HOST = "0.0.0.0"
    const val ADMIN_HOST = "127.0.0.1"
    const val CAPTIVE_CORE_HTTP_PORT = 0
    const val PREFLIGHT_WORKER_COUNT = 8
    const val PREFLIGHT_WORKER_QUEUE_SIZE = 32
    const val PREFLIGHT_ENABLE_DEBUG = false
    const val REQUEST_BACKLOG_GLOBAL_QUEUE_LIMIT = 10_000
    const val MAX_EVENTS_LIMIT = 10_000
    const val DEFAULT_EVENTS_LIMIT = 100
    const val MAX_TRANSACTIONS_LIMIT = 200
    const val MAX_LEDGERS_LIMIT = 200
    const val MAX_REQUEST_EXECUTION_DURATION = "25s"
    const val MAX_HEALTHY_LEDGER_LATENCY = "30s"
    const val RELATIVE_LOG = "logs/stellar-rpc.log"
    const val RPC_BIN = "bin/stellar-rpc"
    const val CORE_BIN = "bin/stellar-core"
    const val RPC_TOML = "stellar-rpc.toml"
    const val CAPTIVE_CORE_CFG = "captive-core.cfg"
    const val HEAL_SCRIPT = "bin/rpcnode-stellar-heal.sh"

    fun execStart(nodeDir: Path): String =
        "${nodeDir.resolve(RPC_BIN)} --config-path ${nodeDir.resolve(RPC_TOML)}"

    fun unit(env: String, nodeDir: String, execStart: String, healScript: String, logFile: String, nofile: Int = NODE_NOFILE): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("stellar", "node.service.tmpl"),
            mapOf(
                "description" to "Stellar RPC + captive core ($env) — RpcNode",
                "node_dir" to nodeDir,
                "exec_start" to execStart,
                "heal_script" to healScript,
                "log_file" to logFile,
                "nofile" to nofile.toString(),
            ),
        )

    fun healScript(logFile: String, stateDir: String, coreStorage: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("stellar", "heal.sh.tmpl"),
            mapOf(
                "log_file" to shellQuote(logFile),
                "state_dir" to shellQuote(stateDir),
                "core_storage" to shellQuote(coreStorage),
            ),
        )

    fun rpcToml(
        cluster: StellarCluster,
        ports: StellarPorts,
        policy: StellarHistoryPolicy,
        nodeDir: Path,
        blockchainDir: Path,
    ): String
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val chain = blockchainDir.toAbsolutePath().normalize()
        val datastore = cluster.datastore?.takeIf { policy.backfillWanted }
        return buildString {
            appendLine("# managed by RpcNode — stellar-rpc (${cluster.env})")
            appendLine("ENDPOINT = ${str("$ENDPOINT_HOST:${ports.http}")}")
            appendLine("ADMIN_ENDPOINT = ${str("$ADMIN_HOST:${ports.admin}")}")
            appendLine("NETWORK_PASSPHRASE = ${str(cluster.passphrase)}")
            appendLine("HISTORY_ARCHIVE_URLS = [${cluster.historyArchiveUrls.joinToString(", ") { str(it) }}]")
            appendLine("STELLAR_CORE_BINARY_PATH = ${str(root.resolve(CORE_BIN).toString())}")
            appendLine("CAPTIVE_CORE_CONFIG_PATH = ${str(root.resolve(CAPTIVE_CORE_CFG).toString())}")
            appendLine("CAPTIVE_CORE_STORAGE_PATH = ${str(chain.resolve("captive-core").toString())}")
            appendLine("DB_PATH = ${str(chain.resolve("stellar_rpc.sqlite").toString())}")
            appendLine("STELLAR_CAPTIVE_CORE_HTTP_PORT = $CAPTIVE_CORE_HTTP_PORT")
            appendLine("STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT = ${ports.coreQuery}")
            appendLine("HISTORY_RETENTION_WINDOW = ${policy.retentionLedgers}")
            if (datastore != null)
            {
                appendLine("BACKFILL = true")
                appendLine("SERVE_LEDGERS_FROM_DATASTORE = true")
            }
            appendLine("PREFLIGHT_WORKER_COUNT = $PREFLIGHT_WORKER_COUNT")
            appendLine("PREFLIGHT_WORKER_QUEUE_SIZE = $PREFLIGHT_WORKER_QUEUE_SIZE")
            appendLine("PREFLIGHT_ENABLE_DEBUG = $PREFLIGHT_ENABLE_DEBUG")
            appendLine("REQUEST_BACKLOG_GLOBAL_QUEUE_LIMIT = $REQUEST_BACKLOG_GLOBAL_QUEUE_LIMIT")
            appendLine("MAX_EVENTS_LIMIT = $MAX_EVENTS_LIMIT")
            appendLine("DEFAULT_EVENTS_LIMIT = $DEFAULT_EVENTS_LIMIT")
            appendLine("MAX_TRANSACTIONS_LIMIT = $MAX_TRANSACTIONS_LIMIT")
            appendLine("MAX_LEDGERS_LIMIT = $MAX_LEDGERS_LIMIT")
            appendLine("MAX_REQUEST_EXECUTION_DURATION = ${str(MAX_REQUEST_EXECUTION_DURATION)}")
            appendLine("MAX_HEALTHY_LEDGER_LATENCY = ${str(MAX_HEALTHY_LEDGER_LATENCY)}")
            appendLine("LOG_LEVEL = \"info\"")
            appendLine("LOG_FORMAT = \"text\"")
            if (datastore != null)
            {
                appendLine()
                appendLine("[datastore_config]")
                appendLine("type = \"S3\"")
                appendLine()
                appendLine("[datastore_config.params]")
                appendLine("destination_bucket_path = ${str(datastore.bucketPath)}")
                appendLine("region = ${str(datastore.region)}")
                appendLine()
                appendLine("[datastore_config.schema]")
                appendLine("ledgers_per_file = 1")
                appendLine("files_per_partition = 64000")
                appendLine()
                appendLine("[buffered_storage_backend_config]")
                appendLine("buffer_size = 100")
                appendLine("num_workers = 10")
                appendLine("retry_limit = 3")
                appendLine("retry_wait = \"5s\"")
            }
        }
    }

    /** TOML basic string. */
    private fun str(raw: String): String = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
}
