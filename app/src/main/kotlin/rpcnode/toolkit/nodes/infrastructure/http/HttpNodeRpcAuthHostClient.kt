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
import rpcnode.toolkit.nodes.application.rpcauth.FetchNodeRpcAuthOnHost
import rpcnode.toolkit.nodes.application.rpcauth.FetchNodeRpcAuthResult
import rpcnode.toolkit.nodes.application.rpcauth.NodeHostRpcAuth

/** GET /api/v1/node/rpc-auth on the host agent. */
class HttpNodeRpcAuthHostClient(
    private val timeout: Duration = Duration.ofSeconds(15),
) : FetchNodeRpcAuthOnHost
{
    private val log = LoggerFactory.getLogger(HttpNodeRpcAuthHostClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    override suspend fun rpcAuth(
        agentUrl: String,
        token: String,
        nodeId: String,
        network: String,
        nodeDir: String?,
    ): FetchNodeRpcAuthResult = withContext(Dispatchers.IO) {
        try
        {
            val parts = mutableListOf(
                "node_id=${URLEncoder.encode(nodeId, StandardCharsets.UTF_8)}",
                "network=${URLEncoder.encode(network, StandardCharsets.UTF_8)}",
            )
            nodeDir?.trim()?.takeIf { it.isNotEmpty() }?.let {
                parts += "node_dir=${URLEncoder.encode(it, StandardCharsets.UTF_8)}"
            }
            val path = "/api/v1/node/rpc-auth?" + parts.joinToString("&")
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
                    val error = obj["error"]?.jsonPrimitive?.contentOrNull
                    if (error == "not_applicable")
                    {
                        return@withContext FetchNodeRpcAuthResult.NotApplicable
                    }
                    if (obj["ok"]?.jsonPrimitive?.contentOrNull == "false")
                    {
                        return@withContext FetchNodeRpcAuthResult.Empty
                    }
                    val user = obj["user"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val password = obj["password"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (user.isEmpty() || password.isEmpty())
                    {
                        return@withContext FetchNodeRpcAuthResult.Empty
                    }
                    FetchNodeRpcAuthResult.Ok(
                        NodeHostRpcAuth(
                            user = user,
                            password = password,
                            path = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        ),
                    )
                }
                401, 403 -> FetchNodeRpcAuthResult.Unauthorized
                404 -> FetchNodeRpcAuthResult.Empty
                else ->
                {
                    log.warn("host agent {} node/rpc-auth: HTTP {}", agentUrl, resp.statusCode())
                    FetchNodeRpcAuthResult.Unreachable
                }
            }
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host agent {} node/rpc-auth: {}", agentUrl, reason)
            FetchNodeRpcAuthResult.Unreachable
        }
    }
}
