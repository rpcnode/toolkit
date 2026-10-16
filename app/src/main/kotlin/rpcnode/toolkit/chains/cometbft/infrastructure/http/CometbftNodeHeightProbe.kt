package rpcnode.toolkit.chains.cometbft.infrastructure.http

import java.time.Duration
import java.time.Instant
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local height from the node's own `/status` (`httpPort` is the CometBFT RPC role port).
 * While state-syncing or catching up `latest_block_height` can be 0 — then the height is reported as unknown (-1).
 */
class CometbftNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
    private val now: () -> Instant = { Instant.now() },
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long? =
        reading(nodeDir, httpPort, configFile, env)?.height?.takeIf { it >= 0 }

    override suspend fun reading(nodeDir: String, httpPort: Int, configFile: String, env: String): HostNodeHeightReading?
    {
        if (httpPort <= 0) return null
        val status = CometbftStatus.fetch(http, "http://127.0.0.1:$httpPort") ?: return null
        return toReading(status, now())
    }

    companion object
    {
        /** A node that is not catching up but whose newest block is minutes old is still waiting for the chain. */
        fun toReading(status: CometbftStatus.Reading, now: Instant): HostNodeHeightReading
        {
            if (status.height <= 0)
            {
                return HostNodeHeightReading(height = -1, syncPct = null, syncing = true)
            }
            val stale = status.latestBlockTime?.let { Duration.between(it, now).seconds > 120 } ?: false
            return HostNodeHeightReading(height = status.height, syncPct = null, syncing = status.catchingUp || stale)
        }
    }
}
