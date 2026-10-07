package rpcnode.toolkit.chains.tron.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Local java-tron height — same `wallet/getnowblock` call as public tip. */
class TronNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long?
    {
        if (httpPort <= 0)
        {
            return null
        }
        return http.tronGetNowBlockHeight("http://127.0.0.1:$httpPort/wallet/getnowblock")
    }
}
