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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.logs.FetchNodeLogsOnHost
import rpcnode.toolkit.nodes.application.logs.FetchNodeLogsResult
import rpcnode.toolkit.nodes.application.logs.NodeHostLogs

/** GET /api/v1/node/logs on the host agent. */
class HttpNodeLogsHostClient(
    private val timeout: Duration = Duration.ofSeconds(15),
) : FetchNodeLogsOnHost
{
    private val log = LoggerFactory.getLogger(HttpNodeLogsHostClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    override suspend fun logs(
        agentUrl: String,
        token: String,
        nodeId: String,
        lines: Int,
        nodeDir: String?,
        logFile: String?,
    ): FetchNodeLogsResult = withContext(Dispatchers.IO) {
        try
        {
            val q = URLEncoder.encode(nodeId, StandardCharsets.UTF_8)
            val parts = mutableListOf("node_id=$q", "lines=$lines")
            nodeDir?.trim()?.takeIf { it.isNotEmpty() }?.let {
                parts += "node_dir=${URLEncoder.encode(it, StandardCharsets.UTF_8)}"
            }
            logFile?.trim()?.takeIf { it.isNotEmpty() }?.let {
                parts += "log_file=${URLEncoder.encode(it, StandardCharsets.UTF_8)}"
            }
            val path = "/api/v1/node/logs?" + parts.joinToString("&")
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
                    val lineEls = obj["lines"]?.jsonArray
                    val linesOut = lineEls?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                    val logs = NodeHostLogs(
                        path = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        lines = linesOut,
                        truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull == true,
                    )
                    if (logs.path.isBlank() && logs.lines.isEmpty())
                    {
                        FetchNodeLogsResult.Empty
                    }
                    else
                    {
                        FetchNodeLogsResult.Ok(logs)
                    }
                }
                401, 403 -> FetchNodeLogsResult.Unauthorized
                404, 409 -> FetchNodeLogsResult.Empty
                else ->
                {
                    log.warn("host agent {} node/logs: HTTP {}", agentUrl, resp.statusCode())
                    FetchNodeLogsResult.Unreachable
                }
            }
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host agent {} node/logs: {}", agentUrl, reason)
            FetchNodeLogsResult.Unreachable
        }
    }
}
