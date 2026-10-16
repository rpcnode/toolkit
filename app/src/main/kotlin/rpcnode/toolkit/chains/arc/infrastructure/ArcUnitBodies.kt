package rpcnode.toolkit.chains.arc.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders the Arc systemd unit and the run script (snapshot bootstrap + EL + CL supervision).
 * Capacity literals must stay identical to `chains/arc/network.yml` clientConfig.bindings.
 */
object ArcUnitBodies
{
    const val NODE_NOFILE = 1_048_576
    const val HTTP_ADDR = "0.0.0.0"
    const val HTTP_API = "eth,net,web3,txpool,trace,debug"
    const val RPC_MAX_CONNECTIONS = 250
    const val RPC_MAX_SUBSCRIPTIONS = 32
    const val BACKPRESSURE_THRESHOLD = 16

    const val RELATIVE_LOG = "logs/arc.log"
    const val RUN_SCRIPT = "bin/rpcnode-arc-run.sh"

    /** `/run/<this>` — IPC sockets between the layers; one per env, a path far below the 108-byte socket limit. */
    fun runtimeDirName(env: String): String = "rpcnode-arc-${ArcClusters.normalizeEnv(env)}"

    fun unit(env: String, nodeDir: String, execStart: String, logFile: String, nofile: Int = NODE_NOFILE): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("arc", "node.service.tmpl"),
            mapOf(
                "description" to "Circle Arc node ($env) — RpcNode",
                "node_dir" to nodeDir,
                "runtime_dir" to runtimeDirName(env),
                "exec_start" to execStart,
                "log_file" to logFile,
                "nofile" to nofile.coerceAtLeast(1024).toString(),
            ),
        )
    }

    fun runScript(
        env: String,
        mode: String,
        execDir: String,
        consDir: String,
        binDir: String,
        stateDir: String,
        logFile: String,
        ports: ArcPorts,
    ): String
    {
        val cluster = ArcClusters.lookup(env)
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("arc", "run.sh.tmpl"),
            mapOf(
                "env" to shellQuote(cluster.env),
                "chain" to shellQuote(cluster.chain),
                "exec_dir" to shellQuote(execDir),
                "cons_dir" to shellQuote(consDir),
                "bin_dir" to shellQuote(binDir),
                "state_dir" to shellQuote(stateDir),
                "log_file" to shellQuote(logFile),
                "run_dir" to shellQuote("/run/${runtimeDirName(env)}"),
                "mode" to shellQuote(ArcNodeMode.normalize(mode)),
                "http_addr" to shellQuote(HTTP_ADDR),
                "http_port" to ports.http.toString(),
                "http_api" to shellQuote(HTTP_API),
                "ws_port" to ports.ws.toString(),
                "el_metrics" to ports.elMetrics.toString(),
                "cl_rpc" to ports.clRpc.toString(),
                "cl_metrics" to ports.clMetrics.toString(),
                "max_connections" to RPC_MAX_CONNECTIONS.toString(),
                "max_subscriptions" to RPC_MAX_SUBSCRIPTIONS.toString(),
                "backpressure" to BACKPRESSURE_THRESHOLD.toString(),
                "forwarder" to shellQuote(cluster.forwarder),
                "relays" to cluster.relays.joinToString(" ") { shellQuote(it) },
            ),
        )
    }

    /** Safe single-token shell quoting for template injection. */
    fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
}
