package rpcnode.toolkit.chains.robinhood.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/** Renders the Robinhood Chain Nitro unit from `resources/chains/robinhood/node.service.tmpl`. */
object RobinhoodUnitBodies
{
    const val LIMIT_NOFILE = "1048576"
    const val RELATIVE_LOG = "logs/node.out"

    fun nitro(
        env: String,
        bin: String,
        datadir: String,
        chainInfo: String,
        l1Rpc: String,
        l1Beacon: String,
        rpcPort: Int,
        wsPort: Int,
        wasmRoots: String,
        snapshot: String,
        logFile: String,
    ): String
    {
        val cluster = RobinhoodClusters.lookup(env)
        val kind = RobinhoodClusters.normalizeSnapshot(snapshot)
        val archive = kind == "archive"
        val feedFlags = cluster.feeds.joinToString("") { "  --node.feed.input.url=$it \\\n" }
        // Nitro resolves <latest-base>/<chain name>/latest-<kind>.txt itself (chain name comes from the chain-info file)
        val initFlags = "  --init.latest=$kind \\\n  --init.latest-base=${RobinhoodClusters.SNAPSHOT_BASE} \\\n"
        val archiveFlags = buildString {
            if (archive)
            {
                append(" \\\n  --execution.caching.archive")
                if (cluster.archivePathScheme)
                {
                    append(" \\\n  --execution.caching.state-scheme=path")
                }
            }
        }
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("robinhood", "node.service.tmpl"),
            mapOf(
                "description" to "Robinhood Chain Nitro ($env, $kind) — RpcNode",
                "bin" to bin,
                "chain_info" to chainInfo,
                "l1_rpc" to l1Rpc,
                "l1_beacon" to l1Beacon,
                "http_port" to rpcPort.toString(),
                "ws_port" to wsPort.toString(),
                "forwarding_target" to cluster.forwardingTarget,
                "feed_flags" to feedFlags,
                "init_flags" to initFlags,
                "datadir" to datadir,
                "wasm_roots" to wasmRoots,
                "archive_flags" to archiveFlags,
                "log_file" to logFile,
                "limit_nofile" to LIMIT_NOFILE,
            ),
        )
    }
}
