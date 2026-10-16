package rpcnode.toolkit.chains.evmfamily.infrastructure.recipe

/** Flag fragments shared by the geth-derived clients (sonicd, story-geth, the Vana geth). */
object GethFlags
{
    /**
     * Public HTTP + WebSocket JSON-RPC on 0.0.0.0 with the [api] namespaces only. `vhosts` / `origins` are `*` because a
     * public endpoint is reached by IP or through a proxy under another Host header; the namespaces are what protects it.
     */
    fun publicRpc(httpPort: Int, wsPort: Int, api: String, equals: Boolean): List<String>
    {
        fun f(name: String, value: String? = null): String = when
        {
            value == null -> name
            equals -> "$name=$value"
            else -> "$name $value"
        }
        return listOf(
            f("--http"),
            f("--http.addr", "0.0.0.0"),
            f("--http.port", httpPort.toString()),
            f("--http.api", api),
            f("--http.vhosts", "*"),
            f("--http.corsdomain", "*"),
            f("--ws"),
            f("--ws.addr", "0.0.0.0"),
            f("--ws.port", wsPort.toString()),
            f("--ws.api", api),
            f("--ws.origins", "*"),
        )
    }

    /** Engine API (authrpc) on loopback with the shared JWT. */
    fun authrpc(port: Int, jwt: String): List<String> = listOf(
        "--authrpc.addr 127.0.0.1",
        "--authrpc.port $port",
        "--authrpc.jwtsecret $jwt",
        "--authrpc.vhosts localhost",
    )

    fun metrics(port: Int): List<String> = listOf("--metrics", "--metrics.addr 127.0.0.1", "--metrics.port $port")
}
