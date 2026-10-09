package rpcnode.toolkit.nodes.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.remove.NodeRemovalOnHost
import rpcnode.toolkit.nodes.application.remove.NodeRemovalView
import rpcnode.toolkit.nodes.application.remove.RemovalOnHostResult
import rpcnode.toolkit.nodes.application.remove.RemovalStepView
import rpcnode.toolkit.nodes.application.remove.StartRemovalOnHostCommand

/** POST /api/v1/node/remove/start and GET /api/v1/node/remove/progress on the host agent. */
class HttpNodeRemovalOnHost(
    private val timeout: Duration = Duration.ofSeconds(30),
) : NodeRemovalOnHost
{
    private val log = LoggerFactory.getLogger(HttpNodeRemovalOnHost::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override suspend fun start(
        agentUrl: String,
        token: String,
        command: StartRemovalOnHostCommand,
    ): RemovalOnHostResult? = call(
        agentUrl,
        token,
        HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + "/api/v1/node/remove/start"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    json.encodeToString(
                        StartPayload(
                            nodeId = command.nodeId,
                            network = command.network,
                            env = command.env,
                            dirs = command.dirs,
                            wipeData = command.wipeData,
                        ),
                    ),
                ),
            ),
    )

    override suspend fun progress(agentUrl: String, token: String, nodeId: String): RemovalOnHostResult? = call(
        agentUrl,
        token,
        HttpRequest.newBuilder(
            URI(agentUrl.trimEnd('/') + "/api/v1/node/remove/progress?node_id=" + URLEncoder.encode(nodeId, Charsets.UTF_8)),
        ).GET(),
    )

    private suspend fun call(agentUrl: String, token: String, builder: HttpRequest.Builder): RemovalOnHostResult? =
        withContext(Dispatchers.IO) {
            try
            {
                val req = builder.timeout(timeout).header("Authorization", "Bearer $token").build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                val status = resp.statusCode()
                if (status == 401)
                {
                    return@withContext RemovalOnHostResult.Failed(
                        "invalid_agent_key",
                        "Invalid agent token — update the server agent key to match the host",
                    )
                }
                val body = runCatching { json.decodeFromString<ResponsePayload>(resp.body()) }.getOrNull()
                if (status == 404 && body?.error.isNullOrBlank() && body?.steps.isNullOrEmpty())
                {
                    // JSON-less 404: the agent jar predates these routes.
                    return@withContext RemovalOnHostResult.Failed(
                        "agent_outdated",
                        "Host agent is too old for step-by-step removal — update rpcnode-agent",
                    )
                }
                if (body?.error == "no_job")
                {
                    return@withContext RemovalOnHostResult.NoJob
                }
                if (status !in 200 until 300 || body == null)
                {
                    val error = body?.error?.takeIf { it.isNotBlank() } ?: "http_$status"
                    return@withContext RemovalOnHostResult.Failed(error, body?.message?.takeIf { it.isNotBlank() } ?: error)
                }
                RemovalOnHostResult.Ok(
                    NodeRemovalView(
                        nodeId = body.nodeId,
                        steps = body.steps.map { RemovalStepView(it.id, it.title, it.status, it.detail) },
                        done = body.done,
                        failed = body.failed,
                        error = body.error.orEmpty(),
                    ),
                )
            }
            catch (e: Exception)
            {
                log.warn("host agent {} node/remove: {}", agentUrl, e.message?.ifBlank { null } ?: e.javaClass.simpleName)
                null
            }
        }
}

@Serializable
private data class StartPayload(
    @SerialName("node_id") val nodeId: String,
    val network: String,
    val env: String,
    val dirs: List<String>,
    @SerialName("wipe_data") val wipeData: Boolean,
)

@Serializable
private data class StepPayload(
    val id: String = "",
    val title: String = "",
    val status: String = "pending",
    val detail: String = "",
)

@Serializable
private data class ResponsePayload(
    @SerialName("node_id") val nodeId: String = "",
    val steps: List<StepPayload> = emptyList(),
    val done: Boolean = false,
    val failed: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)
