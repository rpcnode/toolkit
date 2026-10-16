package rpcnode.toolkit.chains.polkadot.infrastructure

import java.security.MessageDigest
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateEnv
import rpcnode.toolkit.chains.substrate.infrastructure.SubstratePorts
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * The PostgreSQL-backed history indexer of a Polkadot node (`install_options.indexer = history`): a small Subsquid "squid"
 * (`chains/polkadot/history/`) whose processor reads blocks, extrinsics and events from THIS node's RPC and writes them to a
 * database of the host's PostgreSQL, and `@subsquid/graphql-server` serving them as GraphQL. Everything runs as plain Node.js
 * processes from a pinned runtime under the indexer dir; no Docker. Why this and not Subscan Essentials / SubQuery:
 * `docs/networks/polkadot-intake.md`.
 *
 * Three units per env: `…-history-prepare` (PostgreSQL role/database/tablespace, Node.js, `npm ci` + `tsc`, schema),
 * `…-history` (the processor) and `…-history-api` (GraphQL). The two long-running ones are confined to loopback by systemd
 * (neither product has a bind-address option). Every constant that is also a `clientConfig.bindings` literal of
 * `chains/polkadot/network.yml` is asserted equal by `PolkadotHistoryTest`.
 */
object PolkadotHistory
{
    const val OPTION_VALUE = "history"
    /** Wizard text input next to the indexer choice: `genesis` | `head` | a block number. */
    const val START_OPTION = "indexer_start_block"

    // --- pinned runtime (nodejs.org/dist/v24.21.0/SHASUMS256.txt, fetched 2026-10-14) ---
    const val NODE_VERSION = "24.21.0"
    const val NODE_SHA256_X64 = "6e1db87ef58b8819e5d5402eff1536491b18edd8eb7bee5ef7897876e88dc5ff"
    const val NODE_SHA256_ARM64 = "724282c3b43aec998aa9527380465b45d229e021b58035f5f4f63095eabfe5d5"

    // --- layout under node_dir / the indexer disk role ---
    const val PREPARE_SCRIPT = "bin/rpcnode-polkadot-history-prepare.sh"
    const val RUN_SCRIPT = "bin/rpcnode-polkadot-history-run.sh"
    const val REMOVE_HOOK = HostNodeLaunchSupport.REMOVE_HOOK
    const val PREPARE_LOG = "logs/polkadot-history-prepare.log"
    const val PROCESSOR_LOG = "logs/polkadot-history.log"
    const val API_LOG = "logs/polkadot-history-api.log"
    const val SQUID_DIR = "squid"
    const val PW_DIR = ".toolkit/polkadot-history"
    const val READY_MARKER = "$PW_DIR/ready"

    // --- the processor (environment of `node lib/main.js`; src/config.ts reads them) ---
    const val RPC_CAPACITY = 10
    /** Requests per second the processor may send to the node; 0 = no client-side limit (the node's own limits apply). */
    const val RPC_RATE_LIMIT = 0
    /** JSON-RPC calls per batch. A recent Polkadot block is ~3 calls and ~260 KB (MEASURED), the node answers at most 15 MB per response. */
    const val RPC_BATCH_SIZE = 50
    const val RPC_REQUEST_TIMEOUT_MS = 60_000
    const val INDEX_EVENTS = "relevant"
    const val INDEX_ARGS = "signed"
    const val PROCESSOR_HEAP_MB = 4096
    const val PROCESSOR_NOFILE = 65_536

    // --- the API (`squid-graphql-server` flags, verified in @subsquid/graphql-server 4.12.1 `--help` / server.js) ---
    const val GQL_MAX_REQUEST_KB = 64
    const val GQL_MAX_ROOT_FIELDS = 10
    /** Rows x selected fields of one response; a query without `limit` is rejected instead of dumping a table. */
    const val GQL_MAX_RESPONSE_NODES = 10_000
    const val GQL_SQL_TIMEOUT_MS = 10_000
    const val GQL_VALIDATION_MAX_ERRORS = 5
    const val GQL_ISOLATION_LEVEL = "REPEATABLE READ"
    const val GQL_CACHE = "in-memory"
    const val GQL_CACHE_TTL_MS = 3_000
    const val GQL_CACHE_MAX_AGE_MS = 3_000
    const val GQL_CACHE_SIZE_MB = 128
    /** `GQL_DB_CONNECTION_POOL_SIZE` — connections of the API to PostgreSQL (vendor default 5). */
    const val GQL_DB_POOL = 20
    const val API_HEAP_MB = 2048
    const val API_NOFILE = 65_536

    // --- PostgreSQL (role-level; the server itself is not reconfigured when it was already there) ---
    const val PG_MAINT_MIN_MB = 256
    /** Processor pool (typeorm default 10) + API pool [GQL_DB_POOL] + migrations / tools. */
    const val PG_CONNECTION_LIMIT = 60
    const val PG_MAX_PARALLEL_MAINTENANCE_WORKERS = 4
    const val PG_RANDOM_PAGE_COST = "1.1"
    const val PG_EFFECTIVE_IO_CONCURRENCY = 200

    /**
     * First block each env can be indexed from. Subsquid has no built-in types bundle for Westend (Polkadot indexes from genesis, VERIFIED; Paseo has v14 metadata from block 0): Westend's metadata was
     * below v14 until block 7,752,186 (VERIFIED by binary search over `state_getMetadata` on westend-rpc.polkadot.io 2026-10-14),
     * the processor also reads the metadata of the block before its first one, and stops with "types bundle is required for westend
     * chain" at 7,752,187 and below; a run from 7,752,190 worked (VERIFIED against westend-rpc.polkadot.io).
     */
    val FIRST_SUPPORTED_BLOCK: Map<String, Long> = mapOf("westend" to 7_752_190L)

    /**
     * The start block of a first run when the operator left the field empty: the current head, so a first deployment indexes
     * recent history only and is usable at once (a genesis backfill is weeks; choose `genesis` or a block number to backfill).
     */
    @Suppress("UNUSED_PARAMETER")
    fun defaultStart(env: SubstrateEnv): String = "head"

    fun enabled(option: String?): Boolean = option?.trim()?.lowercase() == OPTION_VALUE

    fun unitName(env: String): String = "rpcnode-polkadot-$env-history.service"
    fun apiUnitName(env: String): String = "rpcnode-polkadot-$env-history-api.service"
    fun prepareUnitName(env: String): String = "rpcnode-polkadot-$env-history-prepare.service"

    /** Role / database / tablespace names of one env in the host's PostgreSQL. */
    fun pgRole(env: String): String = "rpcnode_polkadot_$env"

    fun allUnits(env: String): List<String> = listOf(prepareUnitName(env), unitName(env), apiUnitName(env))

    /**
     * What the operator typed for the start block, checked: `genesis`, `head` or a decimal block number; empty = [defaultStart].
     * Returns the normalized value, or throws [IllegalArgumentException] with the text Start shows.
     */
    fun normalizeStart(env: SubstrateEnv, raw: String?): String
    {
        val v = raw?.trim()?.lowercase().orEmpty().ifEmpty { defaultStart(env) }
        val first = FIRST_SUPPORTED_BLOCK[env.id]
        return when
        {
            v == "head" || v == "latest" -> "head"
            v == "genesis" || v == "0" ->
            {
                require(first == null) {
                    "the history indexer cannot start at genesis on ${env.id}: its metadata is older than v14 below block $first and the processor has no types bundle for it — use «head» or a block number >= $first"
                }
                "genesis"
            }
            Regex("[0-9]{1,10}").matches(v) && v.toLong() <= Int.MAX_VALUE ->
            {
                require(first == null || v.toLong() >= first) {
                    "the history indexer cannot start below block $first on ${env.id} (older metadata has no types bundle) — got $v"
                }
                v.trimStart('0')
            }
            else -> throw IllegalArgumentException("indexer start block must be «genesis», «head» or a block number, got «${raw?.trim()}»")
        }
    }

    /**
     * Environment of the processor (the script exports each as `K='v'`). [startBlock] is the normalized option; it is honoured by a
     * first run only (src/start.ts). `NODE_RPC_URL` is the loopback URL of this node's own port.
     */
    fun processorEnvironment(env: SubstrateEnv, ports: SubstratePorts, startBlock: String): LinkedHashMap<String, String> = linkedMapOf(
        "NODE_RPC_URL" to "http://127.0.0.1:${ports.rpc}",
        "START_BLOCK" to startBlock,
        "SS58_PREFIX" to env.ss58Prefix.toString(),
        "RPC_CAPACITY" to RPC_CAPACITY.toString(),
        "RPC_RATE_LIMIT" to RPC_RATE_LIMIT.toString(),
        "RPC_BATCH_SIZE" to RPC_BATCH_SIZE.toString(),
        "RPC_REQUEST_TIMEOUT_MS" to RPC_REQUEST_TIMEOUT_MS.toString(),
        "INDEX_EVENTS" to INDEX_EVENTS,
        "INDEX_ARGS" to INDEX_ARGS,
        "PROCESSOR_PROMETHEUS_PORT" to ports.historyMetrics.toString(),
        "NODE_OPTIONS" to "--max-old-space-size=$PROCESSOR_HEAP_MB",
    )

    /** Environment of the API process. */
    fun apiEnvironment(ports: SubstratePorts): LinkedHashMap<String, String> = linkedMapOf(
        "GQL_PORT" to ports.historyApi.toString(),
        "GQL_DB_CONNECTION_POOL_SIZE" to GQL_DB_POOL.toString(),
        "NODE_OPTIONS" to "--max-old-space-size=$API_HEAP_MB",
    )

    /** `squid-graphql-server` options, name (without `--`) → value; rendered as `--name=value`. */
    fun apiFlags(): LinkedHashMap<String, String> = linkedMapOf(
        "max-request-size" to GQL_MAX_REQUEST_KB.toString(),
        "max-root-fields" to GQL_MAX_ROOT_FIELDS.toString(),
        "max-response-size" to GQL_MAX_RESPONSE_NODES.toString(),
        "sql-statement-timeout" to GQL_SQL_TIMEOUT_MS.toString(),
        "validation-max-errors" to GQL_VALIDATION_MAX_ERRORS.toString(),
        "isolation-level" to GQL_ISOLATION_LEVEL,
        "dumb-cache" to GQL_CACHE,
        "dumb-cache-ttl" to GQL_CACHE_TTL_MS.toString(),
        "dumb-cache-max-age" to GQL_CACHE_MAX_AGE_MS.toString(),
        "dumb-cache-size" to GQL_CACHE_SIZE_MB.toString(),
    )

    fun apiArgs(): String = apiFlags().entries.joinToString(" ") { (k, v) -> shellQuote("--$k=$v") }

    private fun exports(env: Map<String, String>): String =
        env.entries.joinToString("\n") { (k, v) -> "  export $k=${shellQuote(v)}" }

    /** The files of the squid project, relative to `chains/polkadot/history/` (a test asserts this is every file there). */
    val PROJECT_FILES: List<String> = listOf(
        "package.json",
        "package-lock.json",
        "tsconfig.json",
        "schema.graphql",
        "src/config.ts",
        "src/decode.ts",
        "src/main.ts",
        "src/start.ts",
        "src/model/index.ts",
        "src/model/generated/index.ts",
        "src/model/generated/marshal.ts",
        "src/model/generated/block.model.ts",
        "src/model/generated/event.model.ts",
        "src/model/generated/extrinsic.model.ts",
        "src/model/generated/transfer.model.ts",
        "db/migrations/1700000000000-Data.js",
    )

    fun projectFile(rel: String): String =
        checkNotNull(PolkadotHistory::class.java.classLoader.getResourceAsStream("chains/polkadot/history/$rel")) {
            "missing chains/polkadot/history/$rel"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    /** sha256 over the names and bytes of the project: a changed project is rebuilt (`npm ci` + `tsc`) on the next Start. */
    fun projectHash(): String
    {
        val md = MessageDigest.getInstance("SHA-256")
        for (rel in PROJECT_FILES)
        {
            md.update(rel.toByteArray())
            md.update(0)
            md.update(projectFile(rel).toByteArray())
            md.update(0)
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    fun prepareScript(env: SubstrateEnv, stateDir: String, indexerDir: String, logFile: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("polkadot", "history-prepare.sh.tmpl"),
            // the shared functions go first so that their own placeholders are filled by the later entries
            mapOf(
                "postgres_functions" to HostSystemdUnitTemplate.load("default", "postgres-functions.sh").trimEnd(),
                "pg_connection_limit" to PG_CONNECTION_LIMIT.toString(),
                "pg_max_parallel_maintenance_workers" to PG_MAX_PARALLEL_MAINTENANCE_WORKERS.toString(),
                "pg_random_page_cost" to PG_RANDOM_PAGE_COST,
                "pg_effective_io_concurrency" to PG_EFFECTIVE_IO_CONCURRENCY.toString(),
                "env" to shellQuote(env.id),
                "state_dir" to shellQuote(stateDir),
                "idx_dir" to shellQuote(indexerDir),
                "log_file" to shellQuote(logFile),
                "node_version" to NODE_VERSION,
                "node_sha256_x64" to NODE_SHA256_X64,
                "node_sha256_arm64" to NODE_SHA256_ARM64,
                "project_hash" to projectHash(),
                "maint_min_mb" to PG_MAINT_MIN_MB.toString(),
            ),
        )

    fun runScript(env: SubstrateEnv, ports: SubstratePorts, nodeDir: String, indexerDir: String, startBlock: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("polkadot", "history-run.sh.tmpl"),
            mapOf(
                "ready_marker" to shellQuote("$nodeDir/$READY_MARKER"),
                "pw_dir" to shellQuote("$nodeDir/$PW_DIR"),
                "idx_dir" to shellQuote(indexerDir),
                "rpc_port" to ports.rpc.toString(),
                "processor_env" to exports(processorEnvironment(env, ports, startBlock)),
                "api_env" to exports(apiEnvironment(ports)),
                "api_args" to apiArgs(),
            ),
        )

    fun removeHook(nodeDir: String, indexerDir: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("polkadot", "history-remove-hook.sh.tmpl"),
            mapOf("pw_dir" to shellQuote("$nodeDir/$PW_DIR"), "idx_dir" to shellQuote(indexerDir)),
        )

    fun prepareUnit(env: SubstrateEnv, execStart: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("polkadot", "history-prepare.service.tmpl"),
            mapOf(
                "description" to "Polkadot history indexer preparation (${env.id}): PostgreSQL, Node.js, build — RpcNode",
                "exec_start" to execStart,
            ),
        )

    fun processorUnit(env: SubstrateEnv, nodeDir: String, execStart: String, logFile: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("polkadot", "history.service.tmpl"),
            mapOf(
                "description" to "Polkadot history indexer processor (${env.id}) — RpcNode",
                "node_unit" to HostNodeLaunchSupport.unitName("polkadot", env.id),
                "prepare_unit" to prepareUnitName(env.id),
                "node_dir" to nodeDir,
                "exec_start" to execStart,
                "log_file" to logFile,
                "nofile" to PROCESSOR_NOFILE.toString(),
            ),
        )

    fun apiUnit(env: SubstrateEnv, nodeDir: String, execStart: String, logFile: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("polkadot", "history-api.service.tmpl"),
            mapOf(
                "description" to "Polkadot history indexer GraphQL API (${env.id}) — RpcNode",
                "node_unit" to HostNodeLaunchSupport.unitName("polkadot", env.id),
                "prepare_unit" to prepareUnitName(env.id),
                "node_dir" to nodeDir,
                "exec_start" to execStart,
                "log_file" to logFile,
                "nofile" to API_NOFILE.toString(),
            ),
        )

    /** Safe single-token shell quoting for template injection. */
    fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
}
