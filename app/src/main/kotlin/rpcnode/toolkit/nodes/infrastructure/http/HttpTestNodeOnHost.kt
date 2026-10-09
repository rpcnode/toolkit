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
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.test.NodeLiveTestCheck
import rpcnode.toolkit.nodes.application.test.TestNodeOnHost
import rpcnode.toolkit.nodes.application.test.TestNodeOnHostCommand
import rpcnode.toolkit.nodes.application.test.TestNodeOnHostResult

/** POST /api/v1/node/test on the host agent. */
class HttpTestNodeOnHost(
    private val timeout: Duration = Duration.ofSeconds(40),
) : TestNodeOnHost
{
    private val log = LoggerFactory.getLogger(HttpTestNodeOnHost::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override suspend fun test(
        agentUrl: String,
        token: String,
        command: TestNodeOnHostCommand,
    ): TestNodeOnHostResult? = withContext(Dispatchers.IO) {
        try
        {
            val body = json.encodeToString(
                TestPayload(
                    nodeId = command.nodeId,
                    network = command.network,
                    env = command.env,
                    nodeDir = command.nodeDir,
                    httpPort = command.httpPort,
                    configFile = command.configFile,
                ),
            )
            val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + "/api/v1/node/test"))
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() == 401)
            {
                return@withContext TestNodeOnHostResult(ok = false, error = "invalid_agent_key")
            }
            val parsed = runCatching { json.decodeFromString(TestResponse.serializer(), resp.body()) }.getOrNull()
            if (parsed == null)
            {
                return@withContext TestNodeOnHostResult(
                    ok = false,
                    error = "http_${resp.statusCode()}",
                    message = if (resp.statusCode() == 404) "agent has no /api/v1/node/test — update the agent" else "",
                )
            }
            TestNodeOnHostResult(
                ok = parsed.ok,
                checks = parsed.checks.map { NodeLiveTestCheck(it.id, it.title, it.ok, it.detail, it.error) },
                error = parsed.error,
                message = parsed.message,
            )
        }
        catch (e: Exception)
        {
            log.warn("host agent {} node/test: {}", agentUrl, e.message?.ifBlank { null } ?: e.javaClass.simpleName)
            null
        }
    }
}

@Serializable
private data class TestPayload(
    @SerialName("node_id") val nodeId: String = "",
    val network: String = "",
    val env: String = "",
    @SerialName("node_dir") val nodeDir: String = "",
    @SerialName("http_port") val httpPort: Int = 0,
    @SerialName("config_file") val configFile: String = "",
)

@Serializable
private data class TestCheckPayload(
    val id: String = "",
    val title: String = "",
    val ok: Boolean = false,
    val detail: String = "",
    val error: String = "",
)

@Serializable
private data class TestResponse(
    val ok: Boolean = false,
    val checks: List<TestCheckPayload> = emptyList(),
    val error: String = "",
    val message: String = "",
)
