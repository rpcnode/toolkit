package rpcnode.toolkit.chains.stellar.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Local stellar-rpc height via `getHealth` (latestLedger). While the node is behind, getHealth answers an error, so the
 * ledger comes from `getLatestLedger` and the node is reported as syncing; while initialising the height is 0.
 */
class StellarNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(4))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long? =
        reading(nodeDir, httpPort, configFile, env)?.height?.takeIf { it >= 0 }

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
        val base = "http://127.0.0.1:$httpPort"
        val health = StellarRpc.health(http, base) ?: return null
        return when
        {
            health.ok -> HostNodeHeightReading(height = health.latestLedger, syncPct = null, syncing = false)
            health.behind ->
                HostNodeHeightReading(height = StellarRpc.latestSequence(http, base) ?: 0, syncPct = null, syncing = true)
            health.initializing -> HostNodeHeightReading(height = 0, syncPct = null, syncing = true)
            else -> null
        }
    }
}
