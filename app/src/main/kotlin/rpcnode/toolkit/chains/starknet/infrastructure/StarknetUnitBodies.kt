package rpcnode.toolkit.chains.starknet.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/** Renders the Juno unit from `resources/chains/starknet/node.service.tmpl`. */
object StarknetUnitBodies
{
    const val RELATIVE_LOG = "logs/node.out"

    fun juno(
        env: StarknetEnv,
        bin: String,
        nodeDir: String,
        datadir: String,
        envFile: String,
        pruneWindow: String?,
        logFile: String,
    ): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("starknet", "node.service.tmpl"),
            mapOf(
                "description" to "Starknet Juno (${env.id}) — RpcNode",
                "bin" to bin,
                "node_dir" to nodeDir,
                "env_file" to envFile,
                "network" to env.network,
                "datadir" to datadir,
                "http_port" to env.ports.http.toString(),
                "ws_port" to env.ports.ws.toString(),
                "metrics_port" to env.ports.metrics.toString(),
                "db_cache_size" to StarknetChain.DB_CACHE_SIZE_MB,
                "rpc_max_block_scan" to StarknetChain.RPC_MAX_BLOCK_SCAN,
                "rpc_request_timeout" to StarknetChain.RPC_REQUEST_TIMEOUT,
                "rpc_max_concurrent_requests" to StarknetChain.RPC_MAX_CONCURRENT_REQUESTS,
                "rpc_max_request_queue" to StarknetChain.RPC_MAX_REQUEST_QUEUE,
                "prune_flags" to if (pruneWindow == null) "" else " \\\n  --prune-mode=$pruneWindow",
                "log_file" to logFile,
                "limit_nofile" to StarknetChain.LIMIT_NOFILE,
            ),
        )
}
