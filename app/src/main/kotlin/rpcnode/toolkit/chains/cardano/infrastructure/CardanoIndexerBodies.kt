package rpcnode.toolkit.chains.cardano.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * The optional indexer companions of a Cardano node (install option `indexer`): Ogmios and cardano-db-sync, their command
 * lines, units and the db-sync scripts. Every constant is the same value as the matching `clientConfig.bindings` literal in
 * `chains/cardano/network.yml` (asserted by `CardanoCatalogTest`).
 */
object CardanoIndexerBodies
{
    // --- Ogmios (CardanoSolutions/ogmios, flags verified against server/src/Ogmios/Options.hs @ v7.0.0) ---
    const val OGMIOS_HOST = "127.0.0.1"
    const val OGMIOS_TIMEOUT_SEC = 90
    const val OGMIOS_MAX_IN_FLIGHT = 1000
    const val OGMIOS_LOG_LEVEL = "Info"
    const val OGMIOS_NOFILE = 1_048_576

    // --- cardano-db-sync (IntersectMBO/cardano-db-sync @ 13.7.2.1) ---
    const val DBSYNC_NOFILE = 1_048_576
    const val DBSYNC_STOP_TIMEOUT_SEC = 300
    const val DBSYNC_IP_ALLOW = "localhost"
    const val DBSYNC_LEDGER_BACKEND = "inmemory"

    // role-level PostgreSQL settings (upstream's docker setup uses maintenance_work_mem=1GB, max_parallel_maintenance_workers=4)
    const val PG_MAINT_MIN_MB = 1024
    const val PG_MAX_PARALLEL_MAINTENANCE_WORKERS = 4
    const val PG_RANDOM_PAGE_COST = "1.1"
    const val PG_EFFECTIVE_IO_CONCURRENCY = 200

    // --- layout under node_dir / the indexer disk role ---
    const val OGMIOS_BIN = "bin/ogmios"
    const val DBSYNC_BIN = "bin/cardano-db-sync"
    const val SCHEMA_DIR = "schema"
    const val DBSYNC_CONFIG = "db-sync-config.json"
    const val LEDGER_STATE_DIR = "ledger-state"
    const val TABLESPACE_DIR = "pgdata"
    const val OGMIOS_LOG = "logs/cardano-ogmios.log"
    const val DBSYNC_LOG = "logs/cardano-dbsync.log"
    const val PREPARE_LOG = "logs/cardano-dbsync-prepare.log"
    const val PREPARE_SCRIPT = "bin/rpcnode-cardano-dbsync-prepare.sh"
    const val RUN_SCRIPT = "bin/rpcnode-cardano-dbsync.sh"
    const val REMOVE_HOOK = HostNodeLaunchSupport.REMOVE_HOOK

    /** `.toolkit/cardano-indexer.json` — what the host checks (diagnostics) need to know about this node. */
    const val STATE_FILE = ".toolkit/cardano-indexer.json"

    /** `.toolkit/cardano-dbsync/` — password, pgpass, pg.env, `ready` marker. 0700. */
    const val PW_DIR = ".toolkit/cardano-dbsync"
    const val READY_MARKER = "$PW_DIR/ready"
    const val PGPASS = "$PW_DIR/pgpass"

    fun ogmiosUnitName(env: String): String = "rpcnode-cardano-${CardanoClusters.normalizeEnv(env)}-ogmios.service"

    fun dbSyncUnitName(env: String): String = "rpcnode-cardano-${CardanoClusters.normalizeEnv(env)}-dbsync.service"

    fun prepareUnitName(env: String): String = "rpcnode-cardano-${CardanoClusters.normalizeEnv(env)}-dbsync-prepare.service"

    fun nodeUnitName(env: String): String = HostNodeLaunchSupport.unitName("cardano", CardanoClusters.normalizeEnv(env))

    fun ogmiosArgs(nodeDir: String, socketPath: String, port: Int): List<String> =
        listOf(
            "--node-socket", socketPath,
            "--node-config", "$nodeDir/${CardanoUnitBodies.CONFIG_DIR}/${CardanoUnitBodies.CONFIG_JSON}",
            "--host", OGMIOS_HOST,
            "--port", port.toString(),
            "--timeout", OGMIOS_TIMEOUT_SEC.toString(),
            "--max-in-flight", OGMIOS_MAX_IN_FLIGHT.toString(),
            "--log-level", OGMIOS_LOG_LEVEL,
        )

    fun dbSyncArgs(nodeDir: String, socketPath: String, stateDir: String): List<String> =
        listOf(
            "--config", "$nodeDir/${CardanoUnitBodies.CONFIG_DIR}/$DBSYNC_CONFIG",
            "--socket-path", socketPath,
            "--state-dir", stateDir,
            "--schema-dir", "$nodeDir/$SCHEMA_DIR",
        )

    fun ogmiosUnit(env: String, nodeDir: String, socketPath: String, execStart: String, logFile: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("cardano", "ogmios.service.tmpl"),
            mapOf(
                "description" to "Ogmios JSON-RPC for Cardano (${CardanoClusters.normalizeEnv(env)}) — RpcNode",
                "node_unit" to nodeUnitName(env),
                "node_dir" to nodeDir,
                "socket_path" to socketPath,
                "exec_start" to execStart,
                "log_file" to logFile,
                "nofile" to OGMIOS_NOFILE.toString(),
            ),
        )

    fun dbSyncUnit(env: String, nodeDir: String, execStart: String, logFile: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("cardano", "dbsync.service.tmpl"),
            mapOf(
                "description" to "Cardano db-sync (${CardanoClusters.normalizeEnv(env)}) — RpcNode",
                "node_unit" to nodeUnitName(env),
                "prepare_unit" to prepareUnitName(env),
                "node_dir" to nodeDir,
                "exec_start" to execStart,
                "stop_timeout" to DBSYNC_STOP_TIMEOUT_SEC.toString(),
                "log_file" to logFile,
                "nofile" to DBSYNC_NOFILE.toString(),
            ),
        )

    fun prepareUnit(env: String, execStart: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("cardano", "dbsync-prepare.service.tmpl"),
            mapOf(
                "description" to "Cardano db-sync PostgreSQL preparation (${CardanoClusters.normalizeEnv(env)}) — RpcNode",
                "exec_start" to execStart,
            ),
        )

    /** Role / database / tablespace names of one env in the host's PostgreSQL. */
    fun pgRole(env: String): String = "rpcnode_cardano_${CardanoClusters.normalizeEnv(env)}"

    fun prepareScript(env: String, stateDir: String, indexerDir: String, logFile: String): String
    {
        val e = CardanoClusters.normalizeEnv(env)
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("cardano", "dbsync-prepare.sh.tmpl"),
            // the shared functions go first so that their own placeholders are filled by the later entries
            mapOf(
                "postgres_functions" to HostSystemdUnitTemplate.load("default", "postgres-functions.sh").trimEnd(),
                "pg_max_parallel_maintenance_workers" to PG_MAX_PARALLEL_MAINTENANCE_WORKERS.toString(),
                "pg_random_page_cost" to PG_RANDOM_PAGE_COST,
                "pg_effective_io_concurrency" to PG_EFFECTIVE_IO_CONCURRENCY.toString(),
                "env" to shellQuote(e),
                "state_dir" to shellQuote(stateDir),
                "idx_dir" to shellQuote(indexerDir),
                "log_file" to shellQuote(logFile),
                "maint_min_mb" to PG_MAINT_MIN_MB.toString(),
            ),
        )
    }

    fun runScript(readyMarker: String, socketPath: String, pgpassFile: String, stateDir: String, execStart: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("cardano", "dbsync-run.sh.tmpl"),
            mapOf(
                "ready_marker" to shellQuote(readyMarker),
                "socket_path" to shellQuote(socketPath),
                "pgpass_file" to shellQuote(pgpassFile),
                "state_dir" to shellQuote(stateDir),
                "exec_start" to execStart,
            ),
        )

    fun removeHook(pwDir: String, indexerDir: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("cardano", "dbsync-remove-hook.sh.tmpl"),
            mapOf(
                "pw_dir" to shellQuote(pwDir),
                "idx_dir" to shellQuote(indexerDir),
            ),
        )

    /** Safe single-token shell quoting for template injection. */
    fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    /** The command line of a script's `exec` line: each argument quoted. */
    fun shellCommand(parts: List<String>): String = parts.joinToString(" ") { shellQuote(it) }
}
