package rpcnode.toolkit.chains.near.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.chains.nonevm.infrastructure.HeightJson
import rpcnode.toolkit.chains.nonevm.infrastructure.HeightReader
import rpcnode.toolkit.chains.nonevm.infrastructure.ReaderTipProbe
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** `status` → `result.sync_info.{latest_block_height, syncing}` (the same JSON-RPC on the node and on every public RPC). */
object NearStatus
{
    val HEIGHT = HeightReader.JsonRpc(method = "status", result = listOf("sync_info", "latest_block_height"))

    data class Reading(val height: Long, val syncing: Boolean)

    fun parse(body: String?): Reading?
    {
        val root = HeightJson.root(body)
        val height = HeightJson.long(root, listOf("result", "sync_info", "latest_block_height")) ?: return null
        return Reading(height, HeightJson.bool(root, listOf("result", "sync_info", "syncing")) ?: false)
    }
}

class NearNodeHeightProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(3))),
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long? =
        reading(nodeDir, httpPort, configFile, env)?.height?.takeIf { it >= 0 }

    override suspend fun reading(nodeDir: String, httpPort: Int, configFile: String, env: String): HostNodeHeightReading?
    {
        if (httpPort <= 0) return null
        val body = http.postJson("http://127.0.0.1:$httpPort/", """{"jsonrpc":"2.0","id":"x","method":"status","params":[]}""")
        val r = NearStatus.parse(body) ?: return null
        return HostNodeHeightReading(height = r.height, syncing = r.syncing)
    }
}

class NearNetworkTipProbe(
    http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    private val delegate = ReaderTipProbe(NearStatus.HEIGHT, http)

    override suspend fun tip(urls: List<String>): Long? = delegate.tip(urls)
}
