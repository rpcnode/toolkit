package rpcnode.toolkit.chains.etc.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders the core-geth systemd unit from `resources/chains/etc/node.service.tmpl`.
 * Every constant here is the same value as the matching `clientConfig.bindings` literal in `network.yml`.
 */
object EtcUnitBodies
{
    const val HTTP_HOST = "0.0.0.0"
    const val HTTP_API = "eth,net,web3"
    const val HTTP_VHOSTS = "*"
    const val WS_API = "eth,net,web3"
    const val WS_ORIGINS = "*"
    const val MAXPEERS = 50
    const val HISTORY_TRANSACTIONS = 0
    const val MESS = true
    const val RPC_GASCAP = 50_000_000
    const val RPC_EVMTIMEOUT = "5s"
    const val RPC_BATCH_REQUEST_LIMIT = 1000
    const val RPC_BATCH_RESPONSE_MAX_SIZE = 25_000_000
    const val RPC_TXFEECAP = 1
    const val TXPOOL_GLOBALSLOTS = 5120
    const val TXPOOL_GLOBALQUEUE = 1024
    const val NODE_NOFILE = 1_048_576
    const val STOP_TIMEOUT_SEC = 300
    const val RELATIVE_LOG = "logs/node.out"
    const val DATADIR = "datadir"

    fun unit(
        env: String,
        nodeDir: String,
        bin: String,
        datadir: String,
        httpPort: Int,
        wsPort: Int,
        p2pPort: Int,
        archive: Boolean,
        logFile: String,
    ): String
    {
        val cluster = EtcClusters.lookup(env)
        val mode = if (archive) "archive" else "full"
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("etc", "node.service.tmpl"),
            mapOf(
                "description" to "Ethereum Classic Core-Geth (${cluster.env}, $mode) — RpcNode",
                "node_dir" to nodeDir,
                "bin" to bin,
                "chain_flag" to cluster.chainFlag,
                "datadir" to datadir,
                "p2p_port" to p2pPort.toString(),
                "syncmode" to if (archive) "full" else "snap",
                "gcmode" to if (archive) "archive" else "full",
                "http_host" to HTTP_HOST,
                "http_port" to httpPort.toString(),
                "http_api" to HTTP_API,
                "http_vhosts" to HTTP_VHOSTS,
                "ws_port" to wsPort.toString(),
                "ws_api" to WS_API,
                "ws_origins" to WS_ORIGINS,
                "cache_mb" to cluster.cacheMb.toString(),
                "maxpeers" to MAXPEERS.toString(),
                "history_transactions" to HISTORY_TRANSACTIONS.toString(),
                "mess" to MESS.toString(),
                "rpc_gascap" to RPC_GASCAP.toString(),
                "rpc_evmtimeout" to RPC_EVMTIMEOUT,
                "rpc_batch_request_limit" to RPC_BATCH_REQUEST_LIMIT.toString(),
                "rpc_batch_response_max_size" to RPC_BATCH_RESPONSE_MAX_SIZE.toString(),
                "rpc_txfeecap" to RPC_TXFEECAP.toString(),
                "txpool_globalslots" to TXPOOL_GLOBALSLOTS.toString(),
                "txpool_globalqueue" to TXPOOL_GLOBALQUEUE.toString(),
                "stop_timeout_sec" to STOP_TIMEOUT_SEC.toString(),
                "log_file" to logFile,
                "nofile" to NODE_NOFILE.toString(),
            ),
        )
    }
}
