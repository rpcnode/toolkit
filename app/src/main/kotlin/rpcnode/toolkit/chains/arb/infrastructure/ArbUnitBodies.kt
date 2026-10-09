package rpcnode.toolkit.chains.arb.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/** Renders Arbitrum Nitro systemd unit from `resources/chains/arb/node.service.tmpl`. */
object ArbUnitBodies
{
    const val LIMIT_NOFILE = "1048576"

    fun nitro(
        env: String,
        bin: String,
        datadir: String,
        envFile: String,
        l1Rpc: String,
        l1Beacon: String,
        cluster: ArbCluster,
        rpcPort: Int,
        wsPort: Int,
        wasmRoots: String,
        archive: Boolean,
        initUrl: String,
        logFile: String,
    ): String
    {
        val kind = if (archive) "archive" else "pruned"
        val initFlags = when
        {
            archive && initUrl.isNotBlank() ->
                "  --init.url=$initUrl \\\n"
            !archive && cluster.initLatest.isNotBlank() ->
                "  --init.latest=${cluster.initLatest} \\\n"
            else -> ""
        }
        val archiveFlags = if (archive)
        {
            " \\\n  --execution.caching.archive \\\n  --execution.caching.state-scheme=path"
        }
        else
        {
            ""
        }
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("arb", "node.service.tmpl"),
            mapOf(
                "description" to "Arbitrum Nitro full node ($env, $kind) — RpcNode",
                "env_file" to envFile,
                "bin" to bin,
                "l1_rpc" to l1Rpc,
                "l1_beacon" to l1Beacon,
                "chain_id" to cluster.chainId,
                "http_port" to rpcPort.toString(),
                "ws_port" to wsPort.toString(),
                "init_flags" to initFlags,
                "datadir" to datadir,
                "wasm_roots" to wasmRoots,
                "archive_flags" to archiveFlags,
                "log_file" to logFile,
                "limit_nofile" to LIMIT_NOFILE,
            ),
        )
    }

    fun nitroEnv(l1Rpc: String, l1Beacon: String, cluster: ArbCluster, initUrl: String, home: String): String
    {
        return buildString {
            appendLine("# managed by rpcnode arb provision")
            appendLine("HOME=$home")
            appendLine("L1_RPC_URL=$l1Rpc")
            appendLine("L1_BEACON_URL=$l1Beacon")
            appendLine("INIT_URL=$initUrl")
            appendLine("CHAIN_ID=${cluster.chainId}")
        }
    }
}
