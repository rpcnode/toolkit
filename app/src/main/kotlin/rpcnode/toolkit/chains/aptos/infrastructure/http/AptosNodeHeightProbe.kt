package rpcnode.toolkit.chains.aptos.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Local Aptos height = REST `ledger_version`; syncing while the latest ledger is more than a minute old. */
class AptosNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
    private val clockMicros: () -> Long = { System.currentTimeMillis() * 1000 },
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
        val info = AptosRest.ledger(http, "http://127.0.0.1:$httpPort") ?: return null
        return toReading(info, clockMicros())
    }

    companion object
    {
        const val SYNC_LAG_MICROS = 60_000_000L

        fun toReading(info: LedgerInfo, nowMicros: Long): HostNodeHeightReading
        {
            val lag = info.timestampMicros?.let { nowMicros - it }
            val syncing = if (lag == null) info.version > 0 else lag > SYNC_LAG_MICROS
            return HostNodeHeightReading(height = info.version, syncPct = null, syncing = syncing)
        }
    }
}
