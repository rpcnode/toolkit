package rpcnode.toolkit.nodes.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.process.ControlNodeProcessOnHost
import rpcnode.toolkit.nodes.application.process.NodeProcessControlResult

/** POST /api/v1/node/process/stop|start on the host agent. */
class HttpNodeProcessControlClient(
    private val timeout: Duration = Duration.ofSeconds(30),
) : ControlNodeProcessOnHost
{
    private val log = LoggerFactory.getLogger(HttpNodeProcessControlClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    override suspend fun control(
        agentUrl: String,
        token: String,
        nodeId: String,
        network: String,
        env: String,
        action: String,
    ): NodeProcessControlResult? = withContext(Dispatchers.IO) {
        val path = when (action.trim().lowercase())
        {
            "stop" -> "/api/v1/node/process/stop"
            "start" -> "/api/v1/node/process/start"
            else -> return@withContext NodeProcessControlResult(
                ok = false,
                error = "bad_action",
                message = "action must be stop or start",
            )
        }
        try
        {
            val body = json.encodeToString(
                NodeControlPayload(
                    nodeId = nodeId,
                    network = network,
                    env = env,
                ),
            )
            val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + path))
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            val obj = runCatching { json.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
            if (resp.statusCode() !in 200 until 300)
            {
                val error = obj?.get("error")?.jsonPrimitive?.contentOrNull ?: "http_${resp.statusCode()}"
                val message = obj?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
                if (resp.statusCode() == 401 || error == "unauthorized" || error == "token_required")
                {
                    return@withContext NodeProcessControlResult(
                        ok = false,
                        error = "invalid_agent_key",
                        message = "Invalid agent token — the host agent rejected the key. Update the server agent key to match the token on the host.",
                    )
                }
                return@withContext NodeProcessControlResult(
                    ok = false,
                    error = error,
                    message = message,
                )
            }
            NodeProcessControlResult(
                ok = obj?.get("ok")?.jsonPrimitive?.booleanOrNull != false,
                pid = obj?.get("pid")?.jsonPrimitive?.longOrNull ?: 0L,
                action = obj?.get("action")?.jsonPrimitive?.contentOrNull.orEmpty(),
                error = obj?.get("error")?.jsonPrimitive?.contentOrNull.orEmpty(),
                message = obj?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host agent {} node/{}: {}", agentUrl, action, reason)
            null
        }
    }
}

@Serializable
private data class NodeControlPayload(
    @SerialName("node_id") val nodeId: String = "",
    val network: String = "",
    val env: String = "",
)
