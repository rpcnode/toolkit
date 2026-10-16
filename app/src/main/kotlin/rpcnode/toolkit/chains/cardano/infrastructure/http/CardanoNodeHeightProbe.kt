package rpcnode.toolkit.chains.cardano.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoClusters
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Local Cardano height from the Prometheus port (`httpPort` here is the `metrics` role port). */
class CardanoNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
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
        val tip = CardanoMetrics.scrape(http, httpPort) ?: return null
        return CardanoMetrics.reading(tip, CardanoClusters.lookup(env), clock())
    }
}
