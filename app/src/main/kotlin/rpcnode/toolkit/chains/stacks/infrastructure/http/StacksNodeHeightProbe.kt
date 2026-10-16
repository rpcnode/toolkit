package rpcnode.toolkit.chains.stacks.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.chains.nonevm.infrastructure.HeightReader
import rpcnode.toolkit.chains.nonevm.infrastructure.ReaderHeightProbe
import rpcnode.toolkit.chains.nonevm.infrastructure.ReaderTipProbe
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** `GET /v2/info` → `stacks_tip_height` on the node and on api.hiro.so alike (`burn_block_height` is the Bitcoin height). */
object StacksInfo
{
    val HEIGHT = HeightReader.GetJson(localPath = "/v2/info", field = listOf("stacks_tip_height"))
}

class StacksNodeHeightProbe(
    http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe by ReaderHeightProbe(StacksInfo.HEIGHT, http)

class StacksNetworkTipProbe(
    http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe by ReaderTipProbe(StacksInfo.HEIGHT, http)
