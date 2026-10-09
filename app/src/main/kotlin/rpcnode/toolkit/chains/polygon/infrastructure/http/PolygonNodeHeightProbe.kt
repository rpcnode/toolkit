package rpcnode.toolkit.chains.polygon.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Local bor height via `eth_blockNumber` on catalog http port. */
class PolygonNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long?
    {
        if (httpPort <= 0)
        {
            return null
        }
        return PolygonBorRpc.blockNumber(http, "http://127.0.0.1:$httpPort")
    }
}
