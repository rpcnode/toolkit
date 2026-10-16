package rpcnode.toolkit.chains.evmfamily.infrastructure.recipe

import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmCapacity

/** Flag fragments of the reth-based clients (tempo, fluent, bera-reth, gravity_node). Names VERIFIED in reth node/core args. */
object RethFlags
{
    /** Public HTTP + WebSocket on 0.0.0.0 with the [api] namespaces only, and the reth RPC capacity caps. */
    fun publicRpc(httpPort: Int, wsPort: Int, api: String): List<String> = listOf(
        "--http",
        "--http.addr 0.0.0.0",
        "--http.port $httpPort",
        "--http.api $api",
        "--ws",
        "--ws.addr 0.0.0.0",
        "--ws.port $wsPort",
        "--ws.api $api",
    )

    fun capacity(): List<String> = listOf(
        "--rpc.max-connections ${EvmCapacity.RETH_MAX_CONNECTIONS}",
        "--rpc.max-response-size ${EvmCapacity.RETH_MAX_RESPONSE_MB}",
        "--rpc.max-blocks-per-filter ${EvmCapacity.RETH_MAX_BLOCKS_PER_FILTER}",
    )

    /** Execution P2P: discovery on the same port as the listener (tcp + udp). */
    fun p2p(port: Int): List<String> = listOf("--port $port", "--discovery.port $port")

    /** Prometheus on loopback (`--metrics <addr:port>`; a bare port would also be loopback). */
    fun metrics(port: Int): List<String> = listOf("--metrics 127.0.0.1:$port")
}
