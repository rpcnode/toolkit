package rpcnode.toolkit.nodes.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.diagnostics.NodeDiagnosticCheck
import rpcnode.toolkit.nodes.application.diagnostics.NodeDiagnosticItem
import rpcnode.toolkit.nodes.application.diagnostics.RunNodeDiagnosticsOnHost
import rpcnode.toolkit.nodes.application.diagnostics.RunNodeDiagnosticsOnHostResult

/** POST /api/v1/node/diagnostics on the host agent. Checks may look up the public IP, hence the longer timeout. */
class HttpNodeDiagnosticsHostClient(
    private val timeout: Duration = Duration.ofSeconds(30),
) : RunNodeDiagnosticsOnHost
{
    private val log = LoggerFactory.getLogger(HttpNodeDiagnosticsHostClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override suspend fun run(
        agentUrl: String,
        token: String,
        nodeId: String,
        checks: List<NodeDiagnosticCheck>,
    ): RunNodeDiagnosticsOnHostResult = withContext(Dispatchers.IO) {
        try
        {
            val body = JsonObject(
                mapOf(
                    "node_id" to JsonPrimitive(nodeId),
                    "checks" to JsonArray(
                        checks.map { c ->
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(c.id),
                                    "port" to JsonPrimitive(c.port),
                                    "protocol" to JsonPrimitive(c.protocol),
                                    "params" to JsonObject(c.params.mapValues { JsonPrimitive(it.value) }),
                                ),
                            )
                        },
                    ),
                ),
            ).toString()
            val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + "/api/v1/node/diagnostics"))
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            when (resp.statusCode())
            {
                in 200 until 300 ->
                {
                    val items = json.parseToJsonElement(resp.body()).jsonObject["items"]?.jsonArray.orEmpty().map { el ->
                        val o = el.jsonObject
                        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
                        NodeDiagnosticItem(id = s("id"), level = s("level"), title = s("title"), detail = s("detail"), hint = s("hint"))
                    }
                    RunNodeDiagnosticsOnHostResult.Ok(items)
                }
                401, 403 -> RunNodeDiagnosticsOnHostResult.Unauthorized
                404, 405 -> RunNodeDiagnosticsOnHostResult.Unsupported
                else ->
                {
                    log.warn("host agent {} node/diagnostics: HTTP {}", agentUrl, resp.statusCode())
                    RunNodeDiagnosticsOnHostResult.Unreachable
                }
            }
        }
        catch (e: Exception)
        {
            log.warn("host agent {} node/diagnostics: {}", agentUrl, e.message ?: e.javaClass.simpleName)
            RunNodeDiagnosticsOnHostResult.Unreachable
        }
    }
}
