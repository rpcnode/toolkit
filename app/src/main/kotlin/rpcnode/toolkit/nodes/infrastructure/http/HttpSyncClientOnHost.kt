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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.config.ClientSyncOnHostCommand
import rpcnode.toolkit.nodes.application.config.ClientSyncOnHostResult
import rpcnode.toolkit.nodes.application.config.SyncClientOnHost

class HttpSyncClientOnHost(
    private val timeout: Duration = Duration.ofMinutes(10),
) : SyncClientOnHost
{
    private val log = LoggerFactory.getLogger(HttpSyncClientOnHost::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build()

    override suspend fun sync(
        agentUrl: String,
        token: String,
        command: ClientSyncOnHostCommand,
    ): ClientSyncOnHostResult? = withContext(Dispatchers.IO) {
        try
        {
            val body = json.encodeToString(
                ClientSyncPayload(
                    network = command.network,
                    env = command.env,
                    nodeDir = command.nodeDir,
                    configAssignments = command.configAssignments,
                    configFormat = command.configFormat,
                    configFile = command.configFile,
                    configIniSection = command.configIniSection,
                    configOmitIniKeys = command.configOmitIniKeys.toList(),
                ),
            )
            val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + "/api/v1/client/sync"))
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            val obj = runCatching { json.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
            if (resp.statusCode() in 200 until 300)
            {
                val files = obj?.get("files")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                ClientSyncOnHostResult.Ok(
                    nodeDir = obj?.get("node_dir")?.jsonPrimitive?.contentOrNull ?: command.nodeDir,
                    files = files,
                    configPath = obj?.get("config_path")?.jsonPrimitive?.contentOrNull,
                )
            }
            else
            {
                val error = obj?.get("error")?.jsonPrimitive?.contentOrNull ?: "http_${resp.statusCode()}"
                val message = obj?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP ${resp.statusCode()}"
                log.warn("host agent {} client/sync: {} {}", agentUrl, error, message)
                ClientSyncOnHostResult.Failed(error = error, message = message)
            }
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host agent {} client/sync: {}", agentUrl, reason)
            null
        }
    }
}

@Serializable
private data class ClientSyncPayload(
    val network: String,
    val env: String,
    @SerialName("node_dir") val nodeDir: String,
    @SerialName("config_assignments") val configAssignments: Map<String, String> = emptyMap(),
    @SerialName("config_format") val configFormat: String = "hoocon",
    @SerialName("config_file") val configFile: String? = null,
    @SerialName("config_ini_section") val configIniSection: String? = null,
    @SerialName("config_omit_ini_keys") val configOmitIniKeys: List<String> = emptyList(),
)
