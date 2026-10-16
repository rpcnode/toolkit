package rpcnode.toolkit.chains.xai.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/** Renders the Xai Nitro unit from `resources/chains/xai/node.service.tmpl`. */
object XaiUnitBodies
{
    const val RELATIVE_LOG = "logs/node.out"

    fun nitro(
        bin: String,
        datadir: String,
        chainInfo: String,
        envFile: String,
        ports: XaiPorts,
        wasmRoots: String,
        logFile: String,
    ): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("xai", "node.service.tmpl"),
            mapOf(
                "description" to "Xai Nitro (Arbitrum One parent) — RpcNode",
                "bin" to bin,
                "env_file" to envFile,
                "chain_info" to chainInfo,
                "chain_name" to XaiChain.CHAIN_NAME,
                "http_port" to ports.http.toString(),
                "ws_port" to ports.ws.toString(),
                "metrics_port" to ports.metrics.toString(),
                "http_api" to XaiChain.HTTP_API,
                "ws_api" to XaiChain.WS_API,
                "forwarding_target" to XaiChain.FORWARDING_TARGET,
                "feed_url" to XaiChain.FEED_URL,
                "das_url_list" to XaiChain.DAS_URL_LIST,
                "rpc_max_batch_response_size" to XaiChain.RPC_MAX_BATCH_RESPONSE_SIZE,
                "filter_log_cache_size" to XaiChain.FILTER_LOG_CACHE_SIZE,
                "persistent_handles" to XaiChain.PERSISTENT_HANDLES,
                "datadir" to datadir,
                "wasm_roots" to wasmRoots,
                "log_file" to logFile,
                "limit_nofile" to XaiChain.LIMIT_NOFILE,
            ),
        )

    /** Content of `.toolkit/xai-parent.env`; [rpc] must already be [XaiParentChain.validate]d. */
    fun parentEnv(rpc: String): String =
        "# managed by rpcnode xai provision — parent-chain RPC (may carry an API key), keep 0600\n" +
            "${XaiChain.PARENT_ENV_KEY}=\"$rpc\"\n"
}
