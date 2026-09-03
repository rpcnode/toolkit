package rpcnode.toolkit.chains.ton.infrastructure

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
