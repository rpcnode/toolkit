package rpcnode.toolkit.chains.ton.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local TON height via TON HTTP API `getMasterchainInfo` → masterchain seqno.
 */
class TonNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(4))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long?
    {
        return reading(nodeDir, httpPort, configFile, env)?.height?.takeIf { it >= 0 }
    }

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
        val seq = TonGetMasterchainInfo.seqno(http, "http://127.0.0.1:$httpPort")
            ?: return null
        // Seqno 0 right after bootstrap is not a healthy tip yet.
        return HostNodeHeightReading(
            height = seq,
            syncPct = null,
            syncing = seq == 0L,
        )
    }
}
