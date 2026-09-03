package rpcnode.toolkit.nodes.infrastructure.http

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.application.update.ClientRollbackOnHostResult
import rpcnode.toolkit.nodes.application.update.ClientUpdateInfo
import rpcnode.toolkit.nodes.application.update.ClientUpdateOnHostCommand
import rpcnode.toolkit.nodes.application.update.ClientUpdateOnHostResult
import rpcnode.toolkit.nodes.application.update.ClientUpdateStatusOnHostResult
import rpcnode.toolkit.nodes.application.update.UpdateClientOnHost

class HttpUpdateClientOnHost(
    private val timeout: Duration = Duration.ofMinutes(2),
) : UpdateClientOnHost
{
    private val log = LoggerFactory.getLogger(HttpUpdateClientOnHost::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    override suspend fun update(
        agentUrl: String,
        token: String,
        command: ClientUpdateOnHostCommand,
    ): ClientUpdateOnHostResult? = withContext(Dispatchers.IO) {
        try
        {
            val body = json.encodeToString(
                ClientUpdatePayload(
                    nodeId = command.nodeId,
                    network = command.network,
                    env = command.env,
                    nodeDir = command.nodeDir,
                    configAssignments = command.configAssignments,
                    configFormat = command.configFormat,
                    configFile = command.configFile,
                    configIniSection = command.configIniSection,
                    configOmitIniKeys = command.configOmitIniKeys.toList(),
                    httpPort = command.httpPort,
                    program = command.program,
                    clientVersion = command.clientVersion,
                    launch = command.launch.toLaunchPayload(),
                    height = command.height.toHeightPayload(),
                ),
            )
            val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + "/api/v1/client/update"))
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            parseAccept(resp.statusCode(), resp.body(), agentUrl)
        }
        catch (e: Exception)
        {
            log.warn("host agent {} client/update: {}", agentUrl, e.message)
            null
        }
    }

    override suspend fun status(
        agentUrl: String,
        token: String,
        nodeId: String,
        network: String,
        env: String,
    ): ClientUpdateStatusOnHostResult? = withContext(Dispatchers.IO) {
        try
        {
            val q = buildString {
                append("node_id=").append(enc(nodeId))
                if (network.isNotBlank()) append("&network=").append(enc(network))
                if (env.isNotBlank()) append("&env=").append(enc(env))
            }
            val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + "/api/v1/client?$q"))
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            val obj = runCatching { json.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
            if (resp.statusCode() in 200 until 300)
            {
                ClientUpdateStatusOnHostResult.Ok(parseInfo(obj?.get("client_update")?.jsonObject))
            }
            else
            {
                ClientUpdateStatusOnHostResult.Failed(
                    error = obj?.get("error")?.jsonPrimitive?.contentOrNull ?: "http_${resp.statusCode()}",
                    message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP ${resp.statusCode()}",
                )
            }
        }
        catch (e: Exception)
        {
            log.warn("host agent {} client status: {}", agentUrl, e.message)
            null
        }
    }

    override suspend fun rollback(
        agentUrl: String,
        token: String,
        nodeId: String,
        network: String,
        env: String,
    ): ClientRollbackOnHostResult? = withContext(Dispatchers.IO) {
        try
        {
            val body = json.encodeToString(
                ClientRollbackPayload(nodeId = nodeId, network = network, env = env),
            )
            val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + "/api/v1/client/update/rollback"))
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            val obj = runCatching { json.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
            if (resp.statusCode() in 200 until 300)
            {
                ClientRollbackOnHostResult.Ok(parseInfo(obj?.get("client_update")?.jsonObject))
            }
            else
            {
                ClientRollbackOnHostResult.Failed(
                    error = obj?.get("error")?.jsonPrimitive?.contentOrNull ?: "http_${resp.statusCode()}",
                    message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP ${resp.statusCode()}",
                )
            }
        }
        catch (e: Exception)
        {
            log.warn("host agent {} client/rollback: {}", agentUrl, e.message)
            null
        }
    }

    private fun parseAccept(status: Int, body: String, agentUrl: String): ClientUpdateOnHostResult
    {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = obj?.get("error")?.jsonPrimitive?.contentOrNull ?: "http_$status"
        val message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP $status"
        return if (status in 200 until 300)
        {
            ClientUpdateOnHostResult.Accepted(parseInfo(obj?.get("client_update")?.jsonObject))
        }
        else
        {
            log.warn("host agent {} client/update: {} {}", agentUrl, error, message)
            ClientUpdateOnHostResult.Failed(error = error, message = message)
        }
    }

    private fun parseInfo(obj: kotlinx.serialization.json.JsonObject?): ClientUpdateInfo
    {
        if (obj == null)
        {
            return ClientUpdateInfo()
        }
        return ClientUpdateInfo(
            local = obj["local"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            latest = obj["latest"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            previousVersion = obj["previous_version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            updateAvailable = obj["update_available"]?.jsonPrimitive?.booleanOrNull ?: false,
            phase = obj["phase"]?.jsonPrimitive?.contentOrNull.orEmpty().ifEmpty { "idle" },
            step = obj["step"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            detail = obj["detail"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            pct = obj["pct"]?.jsonPrimitive?.intOrNull ?: 0,
            lastError = obj["last_error"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            logTail = obj["log_tail"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    private fun enc(v: String): String = URLEncoder.encode(v, StandardCharsets.UTF_8)
}

private fun NodeLaunchSpec.toLaunchPayload() = UpdateLaunchPayload(
    kind = kind,
    entry = entry,
    args = args,
    extractArchiveGlob = extractArchiveGlob,
    normalizeDir = normalizeDir,
    javaMajor = javaMajor,
    logFile = logFile,
)

private fun NodeHeightSpec.toHeightPayload() = UpdateHeightPayload(
    kind = kind,
    portRole = portRole,
)

@Serializable
private data class ClientUpdatePayload(
    @SerialName("node_id") val nodeId: String,
    val network: String,
    val env: String,
    @SerialName("node_dir") val nodeDir: String,
    @SerialName("config_assignments") val configAssignments: Map<String, String> = emptyMap(),
    @SerialName("config_format") val configFormat: String = "hoocon",
    @SerialName("config_file") val configFile: String? = null,
    @SerialName("config_ini_section") val configIniSection: String? = null,
    @SerialName("config_omit_ini_keys") val configOmitIniKeys: List<String> = emptyList(),
    @SerialName("http_port") val httpPort: Int = 0,
    val program: String = "",
    @SerialName("client_version") val clientVersion: String = "",
    val launch: UpdateLaunchPayload,
    val height: UpdateHeightPayload,
)

@Serializable
private data class UpdateLaunchPayload(
    val kind: String,
    val entry: String,
    val args: List<String> = emptyList(),
    @SerialName("extract_archive_glob") val extractArchiveGlob: String? = null,
    @SerialName("normalize_dir") val normalizeDir: String? = null,
    @SerialName("java_major") val javaMajor: Int? = null,
    @SerialName("log_file") val logFile: String? = null,
)

@Serializable
private data class UpdateHeightPayload(
    val kind: String,
    @SerialName("port_role") val portRole: String = "",
)

@Serializable
private data class ClientRollbackPayload(
    @SerialName("node_id") val nodeId: String,
    val network: String = "",
    val env: String = "",
)
