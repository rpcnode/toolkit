package rpcnode.toolkit.chains.ton.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders TON bootstrap / node-start scripts and systemd units.
 * Capacity literals must stay identical to `chains/ton/network.yml` clientConfig.bindings.
 */
object TonUnitBodies
{
    const val VALIDATOR_NOFILE = 4_194_304
    const val NR_OPEN = 8_388_608
    const val ARCHIVE_TTL_SEC = 2_592_000
    const val STATE_TTL_SEC = 86_400
    const val RELATIVE_LOG = "logs/ton.log"
    const val BOOTSTRAP_SCRIPT = "bin/rpcnode-ton-bootstrap.sh"
    const val NODE_START_SCRIPT = "bin/rpcnode-ton-node-start.sh"
    const val BOOTSTRAP_DONE = ".toolkit/bootstrap.done"
    const val INDEXER_SCRIPT = "bin/rpcnode-ton-indexer.sh"
    const val INDEXER_LOG = "logs/ton-indexer.log"

    /** toncenter/ton-indexer release tag built on the host (worker, classifier, API); override with RPCNODE_TON_INDEXER_TAG. */
    const val INDEXER_RELEASE_TAG = "v1.2.8"

    /** Run by the agent when the node is removed (after the node is stopped, before its files go): indexer DB + MyTonCtrl. */
    const val REMOVE_HOOK_SCRIPT = HostNodeLaunchSupport.REMOVE_HOOK

    /** Go toolchain unpacked next to the indexer (newer ones are fetched by Go itself if go.mod asks for it). */
    const val INDEXER_GO_VERSION = "1.24.4"

    /** The indexer starts once the node is at most this many seconds behind the network. */
    const val INDEXER_MAX_LAG_SEC = 120

    fun bootstrapUnitName(env: String): String
    {
        val e = TonClusters.normalizeEnv(env)
        return "rpcnode-ton-$e-bootstrap.service"
    }

    fun nodeUnit(
        env: String,
        nodeDir: String,
        execStart: String,
        logFile: String,
        nofile: Int = VALIDATOR_NOFILE,
    ): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("ton", "node.service.tmpl"),
            mapOf(
                "description" to "Toncoin liteserver + TON HTTP API ($env) — RpcNode",
                "node_dir" to nodeDir,
                "exec_start" to execStart,
                "log_file" to logFile,
                "nofile" to nofile.coerceAtLeast(1024).toString(),
            ),
        )
    }

    fun indexerUnitName(env: String): String = "rpcnode-ton-${TonClusters.normalizeEnv(env)}-indexer.service"

    fun indexerUnit(env: String, execStart: String): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("ton", "indexer.service.tmpl"),
            mapOf(
                "description" to "TON v3 indexer (toncenter, built from source) ($env) — RpcNode",
                "exec_start" to execStart,
            ),
        )
    }

    fun indexerScript(
        env: String,
        stateDir: String,
        indexerDir: String,
        logFile: String,
        bootstrapMarker: String,
        ports: TonPorts.Ports,
    ): String
    {
        val e = TonClusters.normalizeEnv(env)
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("ton", "indexer.sh.tmpl"),
            mapOf(
                "env" to shellQuote(e),
                "state_dir" to shellQuote(stateDir),
                "idx_dir" to shellQuote(indexerDir),
                "log_file" to shellQuote(logFile),
                "bootstrap_marker" to shellQuote(bootstrapMarker),
                "http_port" to ports.http.toString(),
                "api_port" to ports.indexer.toString(),
                "redis_port" to ports.indexerRedis.toString(),
                "is_testnet" to if (e == "testnet") "1" else "0",
                "max_lag" to INDEXER_MAX_LAG_SEC.toString(),
                "release_tag" to INDEXER_RELEASE_TAG,
                "go_version" to INDEXER_GO_VERSION,
            ),
        )
    }

    fun removeHook(stateDir: String, indexerDir: String): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("ton", "remove-hook.sh.tmpl"),
            mapOf(
                "pw_dir" to shellQuote("$stateDir/ton-indexer"),
                "idx_dir" to shellQuote(indexerDir),
            ),
        )
    }

    fun bootstrapUnit(
        env: String,
        execStart: String,
        nofile: Int = VALIDATOR_NOFILE,
    ): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("ton", "bootstrap.service.tmpl"),
            mapOf(
                "description" to "RpcNode MyTonCtrl liteserver bootstrap (ton/$env)",
                "exec_start" to execStart,
                "nofile" to nofile.coerceAtLeast(1024).toString(),
            ),
        )
    }

    fun bootstrapScript(
        env: String,
        data: String,
        markerDir: String,
        logDir: String,
        thaPort: Int,
        p2pPort: Int,
        history: String,
    ): String
    {
        val e = TonClusters.normalizeEnv(env)
        val mode = TonInstallOptions.normalize(history)
        val extra = TonInstallOptions.installExtra(mode)
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("ton", "bootstrap.sh.tmpl"),
            mapOf(
                "install_mode" to mode,
                "env" to shellQuote(e),
                "chain" to shellQuote(TonClusters.chainFlag(e)),
                "p2p" to p2pPort.toString(),
                "tha_port" to thaPort.toString(),
                "data" to shellQuote(data),
                "marker_dir" to shellQuote(markerDir),
                "log_dir" to shellQuote(logDir),
                "archive_ttl" to ARCHIVE_TTL_SEC.toString(),
                "state_ttl" to STATE_TTL_SEC.toString(),
                "nofile" to VALIDATOR_NOFILE.toString(),
                "nr_open" to NR_OPEN.toString(),
                "install_url" to shellQuote(TonClusters.INSTALL_URL),
                "install_extra" to shellQuote(extra),
                "global_config_url" to shellQuote(TonClusters.globalConfigUrl(e)),
                "zero_state_hash" to shellQuote(TonClusters.zeroStateHash(e)),
                "clang_functions" to HostSystemdUnitTemplate.load("default", "clang-functions.sh").trimEnd(),
            ),
        )
    }

    fun nodeStartScript(markerPath: String, bootstrapUnit: String, logFile: String): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("ton", "node-start.sh.tmpl"),
            mapOf(
                "marker" to shellQuote(markerPath),
                "bootstrap_unit" to shellQuote(bootstrapUnit),
                "log_file" to shellQuote(logFile),
            ),
        )
    }

    /** Safe single-token shell quoting for template injection. */
    fun shellQuote(raw: String): String
    {
        return "'" + raw.replace("'", "'\\''") + "'"
    }
}
