package rpcnode.toolkit.chains.nonevm.infrastructure

import java.time.Duration
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Local height of a node = [reader] against `http://127.0.0.1:<http port>`. */
class ReaderHeightProbe(
    private val reader: HeightReader,
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long?
    {
        if (httpPort <= 0)
        {
            return null
        }
        return reader.read(http, reader.localUrl(httpPort))?.takeIf { it >= 0 }
    }

    override suspend fun reading(nodeDir: String, httpPort: Int, configFile: String, env: String): HostNodeHeightReading? =
        height(nodeDir, httpPort, configFile, env)?.let { HostNodeHeightReading(height = it) }
}

/** Public tip = the same [reader] against the first of `publicTip.urls` that answers with a positive height. */
class ReaderTipProbe(
    private val reader: HeightReader,
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    override suspend fun tip(urls: List<String>): Long?
    {
        for (url in urls)
        {
            if (url.isBlank()) continue
            val h = reader.read(http, url.trim()) ?: continue
            if (h > 0) return h
        }
        return null
    }
}
