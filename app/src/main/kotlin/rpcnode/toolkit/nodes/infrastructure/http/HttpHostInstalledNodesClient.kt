package rpcnode.toolkit.nodes.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.discover.FetchHostInstalledNodes
import rpcnode.toolkit.nodes.application.discover.FetchHostInstalledNodesResult
import rpcnode.toolkit.nodes.application.discover.HostInstalledNode
import rpcnode.toolkit.shared.infrastructure.log.HttpIoLog

/** GET /api/v1/nodes on the host agent. */
class HttpHostInstalledNodesClient(
    private val timeout: Duration = Duration.ofSeconds(10),
) : FetchHostInstalledNodes
{
    private val log = LoggerFactory.getLogger(HttpHostInstalledNodesClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(timeout)
        .build()

    override suspend fun list(agentUrl: String, token: String): FetchHostInstalledNodesResult =
        withContext(Dispatchers.IO) {
            val url = agentUrl.trimEnd('/') + "/api/v1/nodes"
            val started = System.nanoTime()
            try
            {
                val req = HttpRequest.newBuilder(URI(url))
                    .timeout(timeout)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                HttpIoLog.outbound("GET", url, resp.statusCode(), (System.nanoTime() - started) / 1_000_000)
                when (resp.statusCode())
                {
                    in 200 until 300 ->
                    {
                        val obj = json.parseToJsonElement(resp.body()).jsonObject
                        if (obj["ok"]?.jsonPrimitive?.contentOrNull == "false")
                        {
                            return@withContext FetchHostInstalledNodesResult.Unreachable(
                                obj["message"]?.jsonPrimitive?.contentOrNull
                                    ?: obj["error"]?.jsonPrimitive?.contentOrNull
                                    ?: "agent error",
                            )
                        }
                        val items = obj["items"]?.jsonArray.orEmpty().mapNotNull { el ->
                            val m = el.jsonObject
                            val network = m["network"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            val env = m["env"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            if (network.isBlank() || env.isBlank())
                            {
                                return@mapNotNull null
                            }
                            HostInstalledNode(
                                network = network,
                                env = env,
                                status = m["status"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                source = m["source"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                publicPort = m["public_port"]?.jsonPrimitive?.intOrNull ?: 0,
                                agentPort = m["agent_port"]?.jsonPrimitive?.intOrNull ?: 0,
                                nodeHttpPort = m["node_http_port"]?.jsonPrimitive?.intOrNull ?: 0,
                                p2pPort = m["p2p_port"]?.jsonPrimitive?.intOrNull ?: 0,
                            )
                        }
                        FetchHostInstalledNodesResult.Ok(items)
                    }
                    401, 403 -> FetchHostInstalledNodesResult.Unauthorized
                    else ->
                    {
                        log.warn("host agent {} /api/v1/nodes: HTTP {}", agentUrl, resp.statusCode())
                        FetchHostInstalledNodesResult.Unreachable("HTTP ${resp.statusCode()}")
                    }
                }
            }
            catch (e: Exception)
            {
                HttpIoLog.outbound("GET", url, 0, (System.nanoTime() - started) / 1_000_000, e.message)
                val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
                log.warn("host agent {} /api/v1/nodes: {}", agentUrl, reason)
                FetchHostInstalledNodesResult.Unreachable(reason)
            }
        }
}
