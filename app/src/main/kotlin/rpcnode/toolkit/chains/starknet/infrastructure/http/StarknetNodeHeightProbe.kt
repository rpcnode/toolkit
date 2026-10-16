package rpcnode.toolkit.chains.starknet.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local Juno height: `starknet_blockNumber` on 127.0.0.1, plus `starknet_syncing` (current vs highest block) for the progress
 * bar. Returns null while the RPC is down or the DB holds no block yet ("There are no blocks").
 */
class StarknetNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long? =
        reading(nodeDir, httpPort, configFile, env)?.height?.takeIf { it >= 0 }

    override suspend fun reading(nodeDir: String, httpPort: Int, configFile: String, env: String): HostNodeHeightReading?
    {
        if (httpPort <= 0)
        {
            return null
        }
        val url = "http://127.0.0.1:$httpPort"
        val block = StarknetRpc.blockNumber(http, url) ?: return null
        return toReading(block, StarknetRpc.syncing(http, url))
    }

    companion object
    {
        fun toReading(block: Long, sync: StarknetRpc.Syncing?): HostNodeHeightReading = when
        {
            // no answer to starknet_syncing: report the height only, the panel compares it with the public tip
            sync == null -> HostNodeHeightReading(height = block)
            !sync.syncing -> HostNodeHeightReading(height = block, syncPct = 100.0, syncing = false)
            else -> HostNodeHeightReading(
                height = maxOf(block, sync.currentBlock ?: block),
                syncPct = sync.pct?.coerceIn(0.0, 99.9),
                syncing = true,
            )
        }
    }
}
