package rpcnode.toolkit.chains.etc.infrastructure.http

import java.nio.file.Path
import java.time.Duration
import rpcnode.toolkit.chains.etc.infrastructure.EtcUnitBodies
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.nodes.infrastructure.host.LogTail
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Local core-geth height via `eth_blockNumber` + `eth_syncing`, snap-sync % from `logs/node.out`. */
class EtcNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long? =
        reading(nodeDir, httpPort, configFile, env)?.height

    override suspend fun reading(
        nodeDir: String,
        httpPort: Int,
        configFile: String,
        env: String,
    ): HostNodeHeightReading?
    {
        if (httpPort <= 0)
        {
            return null
        }
        val url = "http://127.0.0.1:$httpPort"
        val block = EthereumEthRpc.blockNumber(http, url)
        val sync = EthereumEthRpc.syncing(http, url)
        val log = LogTail.read(Path.of(nodeDir.trim(), EtcUnitBodies.RELATIVE_LOG), 256 * 1024)
        return EtcSyncReading.combine(block, sync, EthereumEthRpc.parseSnapSyncPctFromLog(log))
    }
}
