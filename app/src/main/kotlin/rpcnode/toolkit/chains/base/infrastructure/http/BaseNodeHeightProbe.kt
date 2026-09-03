package rpcnode.toolkit.chains.base.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Local Base base-reth-node height via `eth_blockNumber` on catalog http port. */
class BaseNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long?
    {
        if (httpPort <= 0)
        {
            return null
        }
        return EthereumEthRpc.blockNumber(http, "http://127.0.0.1:$httpPort")
    }
}
