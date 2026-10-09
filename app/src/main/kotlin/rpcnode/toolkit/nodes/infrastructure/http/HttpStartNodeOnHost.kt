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
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.application.start.StartNodeOnHost
import rpcnode.toolkit.nodes.application.start.StartNodeOnHostCommand
import rpcnode.toolkit.nodes.application.start.StartNodeOnHostResult

class HttpStartNodeOnHost(
    /**
     * Host Start should return quickly (Solana Agave cargo-build runs in the background).
     * Override with TOOLKIT_NODE_START_TIMEOUT_MIN if extract/unit install needs longer.
     */
    private val timeout: Duration = Duration.ofMinutes(
        System.getenv("TOOLKIT_NODE_START_TIMEOUT_MIN")?.toLongOrNull()?.coerceAtLeast(1) ?: 10,
    ),
) : StartNodeOnHost
{
    private val log = LoggerFactory.getLogger(HttpStartNodeOnHost::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    override suspend fun start(
        agentUrl: String,
        token: String,
        command: StartNodeOnHostCommand,
    ): StartNodeOnHostResult? = withContext(Dispatchers.IO) {
        try
        {
            val body = json.encodeToString(
                StartNodePayload(
                    nodeId = command.nodeId,
                    network = command.network,
                    env = command.env,
                    nodeDir = command.nodeDir,
                    configFile = command.configFile,
                    httpPort = command.httpPort,
                    program = command.program,
                    clientVersion = command.clientVersion,
                    launch = command.launch.toPayload(),
                    height = command.height.toPayload(),
                ),
            )
            val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + "/api/v1/node/start"))
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            val obj = runCatching { json.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
            val error = obj?.get("error")?.jsonPrimitive?.contentOrNull ?: "http_${resp.statusCode()}"
            val message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP ${resp.statusCode()}"
            if (
                resp.statusCode() == 202 ||
                error.equals("client_build_pending", ignoreCase = true)
            )
            {
                StartNodeOnHostResult.Pending(error = error, message = message)
            }
            else if (resp.statusCode() in 200 until 300)
            {
                val pid = obj?.get("pid")?.jsonPrimitive?.longOrNull ?: 0L
                val already = obj?.get("already_running")?.jsonPrimitive?.booleanOrNull ?: false
                StartNodeOnHostResult.Ok(pid = pid, alreadyRunning = already)
            }
            else
            {
                log.warn("host agent {} node/start: {} {}", agentUrl, error, message)
                StartNodeOnHostResult.Failed(error = error, message = message)
            }
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host agent {} node/start: {}", agentUrl, reason)
            null
        }
    }
}

private fun NodeLaunchSpec.toPayload() = LaunchPayload(
    kind = kind,
    entry = entry,
    args = args,
    extractArchiveGlob = extractArchiveGlob,
    normalizeDir = normalizeDir,
    javaMajor = javaMajor,
    logFile = logFile,
)

private fun NodeHeightSpec.toPayload() = HeightPayload(
    kind = kind,
    portRole = portRole,
)

@Serializable
private data class LaunchPayload(
    val kind: String,
    val entry: String,
    val args: List<String> = emptyList(),
    @SerialName("extract_archive_glob") val extractArchiveGlob: String? = null,
    @SerialName("normalize_dir") val normalizeDir: String? = null,
    @SerialName("java_major") val javaMajor: Int? = null,
    @SerialName("log_file") val logFile: String? = null,
)

@Serializable
private data class HeightPayload(
    val kind: String,
    @SerialName("port_role") val portRole: String = "",
)

@Serializable
private data class StartNodePayload(
    @SerialName("node_id") val nodeId: String,
    val network: String,
    val env: String,
    @SerialName("node_dir") val nodeDir: String,
    @SerialName("config_file") val configFile: String? = null,
    @SerialName("http_port") val httpPort: Int = 0,
    val program: String = "",
    @SerialName("client_version") val clientVersion: String = "",
    val launch: LaunchPayload,
    val height: HeightPayload,
)
