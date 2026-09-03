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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.remove.RemoveNodeOnHost
import rpcnode.toolkit.nodes.application.remove.RemoveNodeOnHostCommand
import rpcnode.toolkit.nodes.application.remove.RemoveNodeOnHostResult

/** POST /api/v1/node/remove on the host agent. */
class HttpRemoveNodeOnHost(
    private val timeout: Duration = Duration.ofMinutes(5),
) : RemoveNodeOnHost
{
    private val log = LoggerFactory.getLogger(HttpRemoveNodeOnHost::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override suspend fun remove(
        agentUrl: String,
        token: String,
        command: RemoveNodeOnHostCommand,
    ): RemoveNodeOnHostResult? = withContext(Dispatchers.IO) {
        try
        {
            val body = json.encodeToString(
                RemovePayload(
                    nodeId = command.nodeId,
                    network = command.network,
                    env = command.env,
                    nodeDir = command.nodeDir,
                    wipeData = command.wipeData,
                ),
            )
            val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + "/api/v1/node/remove"))
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            val obj = runCatching { json.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
            if (resp.statusCode() in 200 until 300)
            {
                return@withContext RemoveNodeOnHostResult.Ok
            }
            val error = obj?.get("error")?.jsonPrimitive?.contentOrNull ?: "http_${resp.statusCode()}"
            val message = obj?.get("message")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (resp.statusCode() == 401 || error == "unauthorized" || error == "token_required")
            {
                return@withContext RemoveNodeOnHostResult.Failed(
                    error = "invalid_agent_key",
                    message = "Invalid agent token — update the server agent key to match the host",
                )
            }
            // Bare 404 (no JSON error) = running agent jar predates POST /api/v1/node/remove.
            if (resp.statusCode() == 404 && obj?.get("error") == null)
            {
                return@withContext RemoveNodeOnHostResult.Failed(
                    error = "agent_outdated",
                    message = "Host agent has no /api/v1/node/remove — rebuild and restart rpcnode-agent",
                )
            }
            RemoveNodeOnHostResult.Failed(error = error, message = message.ifBlank { error })
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host agent {} node/remove: {}", agentUrl, reason)
            null
        }
    }
}

@Serializable
private data class RemovePayload(
    @SerialName("node_id") val nodeId: String = "",
    val network: String = "",
    val env: String = "",
    @SerialName("node_dir") val nodeDir: String? = null,
    @SerialName("wipe_data") val wipeData: Boolean = false,
)
