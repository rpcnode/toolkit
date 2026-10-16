package rpcnode.toolkit.chains.opstack.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders the systemd unit and the run script (binaries + snapshot + EL/op-node supervision) of an OP Stack network.
 * Capacity literals must stay identical to `chains/<id>/network.yml` clientConfig.bindings (a catalog test compares them).
 */
object OpstackUnitBodies
{
    const val NODE_NOFILE = 1_048_576

    /** Public JSON-RPC / WebSocket bind. Everything else (Engine API, metrics, op-node RPC) stays on [LOOPBACK]. */
    const val HTTP_ADDR = "0.0.0.0"
    const val LOOPBACK = "127.0.0.1"

    /** Narrow public namespaces: debug / trace / txpool / admin are not served on the public port. */
    const val HTTP_API = "eth,net,web3"

    /** op-node `--verifier.l1-confs` (op-node's own default is 0; Unichain / Lisk / Conduit all run 4). */
    const val L1_CONFS = 4

    fun unit(spec: OpstackNetworkSpec, env: String, nodeDir: String, execStart: String, logFile: String, nofile: Int = NODE_NOFILE): String
    {
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load(spec.network.value, "node.service.tmpl"),
            mapOf(
                "description" to "${spec.label} ${spec.elFlavor.binary} + op-node ($env) — RpcNode",
                "node_dir" to nodeDir,
                "exec_start" to execStart,
                "log_file" to logFile,
                "nofile" to nofile.coerceAtLeast(1024).toString(),
            ),
        )
    }

    /** Where the run script puts everything of one node. */
    data class Layout(
        val nodeDir: String,
        val execDir: String,
        val consDir: String,
        val snapDir: String,
        val stateDir: String,
        val logFile: String,
    )

    fun runScript(
        spec: OpstackNetworkSpec,
        envId: String,
        options: OpstackInstallOptions,
        layout: Layout,
        l1Rpc: String,
        l1Beacon: String,
    ): String
    {
        val env = spec.env(envId)
        val mode = OpstackNodeMode.normalize(options.node)
        val ports = env.ports
        val (kind, ref, path) = when (val s = env.snapshot)
        {
            OpstackSnapshotSource.None -> Triple("none", "", "")
            is OpstackSnapshotSource.Alchemy -> Triple("alchemy", s.page, s.path)
            is OpstackSnapshotSource.Gelato -> Triple("gelato", s.host, "")
            is OpstackSnapshotSource.Fixed -> Triple("fixed", "", "")
        }
        val fixed = env.snapshot as? OpstackSnapshotSource.Fixed
        val wanted = OpstackRestoreMode.normalize(options.restore) == OpstackRestoreMode.SNAPSHOT
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("opstack", "run.sh.tmpl"),
            mapOf(
                "net_id" to q(spec.network.value),
                "env" to q(env.id),
                "mode" to q(mode),
                "el_flavor" to q(if (spec.elFlavor == OpstackElFlavor.WORLD_CHAIN) "world-chain" else "op-reth"),
                "el_bin_name" to q(spec.elFlavor.binary),
                "reth_chain" to q(env.rethChain),
                "node_network" to q(env.nodeNetwork),
                "sequencer" to q(env.sequencer),
                "sync_mode" to q(env.syncMode.flag),
                "snapshot_wanted" to if (wanted) "1" else "0",
                "snap_kind" to q(kind),
                "snap_ref" to q(ref),
                "snap_path" to q(path),
                "snap_url_full" to q(fixed?.full.orEmpty()),
                "snap_url_archive" to q(fixed?.archive.orEmpty()),
                "snap_custom_url" to q(options.snapshotUrl.trim()),
                "node_dir" to q(layout.nodeDir),
                "exec_dir" to q(layout.execDir),
                "cons_dir" to q(layout.consDir),
                "snap_dir" to q(layout.snapDir),
                "state_dir" to q(layout.stateDir),
                "log_file" to q(layout.logFile),
                "l1_rpc" to q(l1Rpc),
                "l1_beacon" to q(l1Beacon),
                "http_addr" to q(HTTP_ADDR),
                "loopback" to q(LOOPBACK),
                "http_port" to ports.http.toString(),
                "ws_port" to ports.ws.toString(),
                "engine_port" to ports.engine.toString(),
                "metrics_port" to ports.metrics.toString(),
                "p2p_port" to ports.p2p.toString(),
                "discv5_port" to ports.discv5.toString(),
                "cl_rpc" to ports.clRpc.toString(),
                "cl_metrics" to ports.clMetrics.toString(),
                "cl_p2p" to ports.clP2p.toString(),
                "http_api" to q(HTTP_API),
                "prune_distance" to env.pruneDistance.toString(),
                "l1_confs" to L1_CONFS.toString(),
                "cl_static" to q(env.clStatic.joinToString(",")),
                "cl_bootnodes" to q(env.clBootnodes.joinToString(",")),
                "el_trusted" to q(env.elTrusted.joinToString(",")),
                "el_extra_flags" to q(env.elExtraFlags.joinToString(" ")),
                "reth_tag" to OpstackToolchain.RETH_TAG,
                "node_tag" to OpstackToolchain.NODE_TAG,
                "go_version" to OpstackToolchain.GO_VERSION,
            ),
        )
    }

    /** Safe single-token shell quoting for template injection. */
    fun q(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
}
