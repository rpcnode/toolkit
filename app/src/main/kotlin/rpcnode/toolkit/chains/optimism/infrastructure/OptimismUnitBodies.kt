package rpcnode.toolkit.chains.optimism.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders the Optimism systemd unit and the run script (build + snapshot + op-reth/op-node supervision).
 * Capacity literals must stay identical to `chains/optimism/network.yml` clientConfig.bindings.
 */
object OptimismUnitBodies
{
    const val NODE_NOFILE = 1_048_576
    const val HTTP_ADDR = "0.0.0.0"
    /** Public namespaces: debug / trace / txpool are the memory and CPU hazard of a public port (prod-rpc-tuning.md). */
    const val HTTP_API = "eth,net,web3"
    const val LOOPBACK = "127.0.0.1"

    /** Blocks of state/receipts a full node keeps (~30 days at 2 s); block bodies are never pruned (op-node needs them). */
    const val PRUNE_DISTANCE = 1_296_000

    /**
     * Pinned releases of the ethereum-optimism/optimism monorepo. The panel compiles them (chains/optimism/clients.yml,
     * which must pin the same tags) and a host without the prebuilt binary builds them itself.
     */
    const val RETH_TAG = "op-reth/v2.6.0"
    const val NODE_TAG = "op-node/v1.19.9"

    /** The monorepo's go.mod toolchain line at those tags. */
    const val GO_VERSION = "1.26.6"
    const val SNAPSHOT_HOST = "datadirs.optimism.io"

    const val RELATIVE_LOG = "logs/optimism.log"
    const val RUN_SCRIPT = "bin/rpcnode-optimism-run.sh"

    fun unit(env: String, nodeDir: String, execStart: String, logFile: String, nofile: Int = NODE_NOFILE): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("optimism", "node.service.tmpl"),
            mapOf(
                "description" to "Optimism op-reth + op-node ($env) — RpcNode",
                "node_dir" to nodeDir,
                "exec_start" to execStart,
                "log_file" to logFile,
                "nofile" to nofile.coerceAtLeast(1024).toString(),
            ),
        )
    }

    fun runScript(
        env: String,
        mode: String,
        nodeDir: String,
        execDir: String,
        consDir: String,
        snapDir: String,
        stateDir: String,
        logFile: String,
        l1Rpc: String,
        l1Beacon: String,
        ports: OptimismPorts,
    ): String
    {
        val cluster = OptimismClusters.lookup(env)
        val normalized = OptimismNodeMode.normalize(mode)
        val snapshotRequired = cluster.snapshotAlways || normalized == OptimismNodeMode.ARCHIVE
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("optimism", "run.sh.tmpl"),
            mapOf(
                "env" to q(cluster.env),
                "mode" to q(normalized),
                "reth_chain" to q(cluster.rethChain),
                "node_network" to q(cluster.nodeNetwork),
                "sequencer" to q(cluster.sequencer),
                "snapshot_net" to q(cluster.snapshotNet),
                "snapshot_required" to if (snapshotRequired) "1" else "0",
                "snapshot_host" to q(SNAPSHOT_HOST),
                "node_dir" to q(nodeDir),
                "exec_dir" to q(execDir),
                "cons_dir" to q(consDir),
                "snap_dir" to q(snapDir),
                "state_dir" to q(stateDir),
                "log_file" to q(logFile),
                "l1_rpc" to q(l1Rpc),
                "l1_beacon" to q(l1Beacon),
                "http_addr" to q(HTTP_ADDR),
                "http_port" to ports.http.toString(),
                "ws_port" to ports.ws.toString(),
                "engine_port" to ports.engine.toString(),
                "metrics_port" to ports.metrics.toString(),
                "p2p_port" to ports.p2p.toString(),
                "cl_rpc" to ports.clRpc.toString(),
                "cl_metrics" to ports.clMetrics.toString(),
                "cl_p2p" to ports.clP2p.toString(),
                "http_api" to q(HTTP_API),
                "loopback" to q(LOOPBACK),
                "discv5_port" to ports.discV5.toString(),
                "prune_distance" to PRUNE_DISTANCE.toString(),
                "reth_tag" to RETH_TAG,
                "node_tag" to NODE_TAG,
                "go_version" to GO_VERSION,
            ),
        )
    }

    /** Safe single-token shell quoting for template injection. */
    fun q(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
}
