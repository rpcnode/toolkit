package rpcnode.toolkit.nodes.infrastructure.http

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.version.FetchNodeClientVersionOnHost
import rpcnode.toolkit.nodes.application.version.FetchNodeClientVersionResult
import rpcnode.toolkit.nodes.application.version.NodeHostClientVersion

/** GET /api/v1/node/client-version on the host agent. */
class HttpNodeClientVersionHostClient(
    private val timeout: Duration = Duration.ofSeconds(15),
) : FetchNodeClientVersionOnHost
{
    private val log = LoggerFactory.getLogger(HttpNodeClientVersionHostClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    override suspend fun clientVersion(
        agentUrl: String,
        token: String,
        nodeId: String,
        nodeDir: String?,
        seed: String?,
    ): FetchNodeClientVersionResult = withContext(Dispatchers.IO) {
        try
        {
            val q = URLEncoder.encode(nodeId, StandardCharsets.UTF_8)
            val parts = mutableListOf("node_id=$q")
            nodeDir?.trim()?.takeIf { it.isNotEmpty() }?.let {
                parts += "node_dir=${URLEncoder.encode(it, StandardCharsets.UTF_8)}"
            }
            seed?.trim()?.takeIf { it.isNotEmpty() }?.let {
                parts += "seed=${URLEncoder.encode(it, StandardCharsets.UTF_8)}"
            }
            val path = "/api/v1/node/client-version?" + parts.joinToString("&")
            val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + path))
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            when (resp.statusCode())
            {
                in 200 until 300 ->
                {
                    val obj = json.parseToJsonElement(resp.body()).jsonObject
                    if (obj["ok"]?.jsonPrimitive?.contentOrNull == "false")
                    {
                        FetchNodeClientVersionResult.Empty
                    }
                    else
                    {
                        FetchNodeClientVersionResult.Ok(
                            NodeHostClientVersion(
                                clientVersion = obj["client_version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                path = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            ),
                        )
                    }
                }
                401, 403 -> FetchNodeClientVersionResult.Unauthorized
                404 -> FetchNodeClientVersionResult.Empty
                else ->
                {
                    log.warn("host agent {} node/client-version: HTTP {}", agentUrl, resp.statusCode())
                    FetchNodeClientVersionResult.Unreachable
                }
            }
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host agent {} node/client-version: {}", agentUrl, reason)
            FetchNodeClientVersionResult.Unreachable
        }
    }
}
