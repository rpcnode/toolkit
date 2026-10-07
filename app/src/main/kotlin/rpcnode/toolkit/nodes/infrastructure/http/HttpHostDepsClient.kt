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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.hostdeps.FetchHostDepsProgressOnHost
import rpcnode.toolkit.nodes.application.hostdeps.FetchHostDepsProgressOnHostResult
import rpcnode.toolkit.nodes.application.hostdeps.HostDepSpec
import rpcnode.toolkit.nodes.application.hostdeps.NodeHostDepProgressItem
import rpcnode.toolkit.nodes.application.hostdeps.NodeHostDepsProgress
import rpcnode.toolkit.nodes.application.hostdeps.ProbeHostDepsOnHost
import rpcnode.toolkit.nodes.application.hostdeps.ProbeHostDepsOnHostResult
import rpcnode.toolkit.nodes.application.hostdeps.StartHostDepsOnHost
import rpcnode.toolkit.nodes.application.hostdeps.StartHostDepsOnHostResult

class HttpHostDepsClient(
    private val timeout: Duration = Duration.ofSeconds(30),
    private val progressTimeout: Duration = Duration.ofSeconds(15),
) : ProbeHostDepsOnHost, StartHostDepsOnHost, FetchHostDepsProgressOnHost
{
    private val log = LoggerFactory.getLogger(HttpHostDepsClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(timeout)
        .build()

    override suspend fun probe(
        agentUrl: String,
        token: String,
        deps: List<HostDepSpec>,
    ): ProbeHostDepsOnHostResult = withContext(Dispatchers.IO) {
        try
        {
            val body = json.encodeToString(
                AgentHostDepsProbeBody(deps = deps.map { it.toBody() }),
            )
            val resp = post(agentUrl, "/api/v1/host/deps/probe", token, body, timeout)
            when (resp.status)
            {
                in 200 until 300 ->
                {
                    val parsed = json.decodeFromString<AgentHostDepsProbeResponse>(resp.body)
                    ProbeHostDepsOnHostResult.Ok(parsed.items.map { it.id to it.present })
                }
                401, 403 -> ProbeHostDepsOnHostResult.Unauthorized
                else ->
                {
                    log.warn("host deps probe HTTP {}", resp.status)
                    ProbeHostDepsOnHostResult.Unreachable
                }
            }
        }
        catch (e: Exception)
        {
            log.warn("host deps probe: {}", e.message)
            ProbeHostDepsOnHostResult.Unreachable
        }
    }

    override suspend fun start(
        agentUrl: String,
        token: String,
        jobId: String,
        deps: List<HostDepSpec>,
    ): StartHostDepsOnHostResult = withContext(Dispatchers.IO) {
        try
        {
            val body = json.encodeToString(
                AgentHostDepsInstallBody(
                    jobId = jobId,
                    deps = deps.map { it.toBody() },
                ),
            )
            val resp = post(agentUrl, "/api/v1/host/deps/install", token, body, timeout)
            when (resp.status)
            {
                in 200 until 300 ->
                {
                    val parsed = json.decodeFromString<AgentHostDepsInstallResponse>(resp.body)
                    val id = parsed.jobId.ifBlank { jobId }
                    StartHostDepsOnHostResult.Ok(id)
                }
                401, 403 -> StartHostDepsOnHostResult.Unauthorized
                else ->
                {
                    log.warn("host deps install HTTP {}", resp.status)
                    StartHostDepsOnHostResult.Unreachable
                }
            }
        }
        catch (e: Exception)
        {
            log.warn("host deps install: {}", e.message)
            StartHostDepsOnHostResult.Unreachable
        }
    }

    override suspend fun progress(
        agentUrl: String,
        token: String,
        jobId: String,
    ): FetchHostDepsProgressOnHostResult = withContext(Dispatchers.IO) {
        try
        {
            val q = URLEncoder.encode(jobId, StandardCharsets.UTF_8)
            val path = "/api/v1/host/deps/progress?job_id=$q"
            val resp = get(agentUrl, path, token, progressTimeout)
            when (resp.status)
            {
                in 200 until 300 ->
                {
                    val parsed = json.decodeFromString<AgentHostDepsProgressResponse>(resp.body)
                    FetchHostDepsProgressOnHostResult.Ok(
                        NodeHostDepsProgress(
                            jobId = parsed.jobId.ifBlank { jobId },
                            phase = parsed.phase,
                            detail = parsed.detail,
                            pct = parsed.pct,
                            currentId = parsed.currentId,
                            items = parsed.items.map {
                                NodeHostDepProgressItem(id = it.id, status = it.status, detail = it.detail)
                            },
                            ready = parsed.ready,
                            failed = parsed.failed,
                            error = parsed.error,
                            logTail = parsed.logTail,
                        ),
                    )
                }
                404 -> FetchHostDepsProgressOnHostResult.JobNotFound
                401, 403 -> FetchHostDepsProgressOnHostResult.Unauthorized
                else ->
                {
                    log.warn("host deps progress HTTP {}", resp.status)
                    FetchHostDepsProgressOnHostResult.Unreachable
                }
            }
        }
        catch (e: Exception)
        {
            log.warn("host deps progress: {}", e.message)
            FetchHostDepsProgressOnHostResult.Unreachable
        }
    }

    private data class Raw(val status: Int, val body: String)

    private fun post(
        agentUrl: String,
        path: String,
        token: String,
        jsonBody: String,
        timeout: Duration,
    ): Raw
    {
        val url = agentUrl.trimEnd('/') + path
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(timeout)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        return Raw(resp.statusCode(), resp.body().orEmpty())
    }

    private fun get(agentUrl: String, path: String, token: String, timeout: Duration): Raw
    {
        val url = agentUrl.trimEnd('/') + path
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(timeout)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        return Raw(resp.statusCode(), resp.body().orEmpty())
    }
}

@Serializable
private data class AgentHostDepBody(
    val id: String = "",
    val kind: String = "package",
    val name: String = "",
    @SerialName("java_major") val javaMajor: Int = 0,
)

@Serializable
private data class AgentHostDepsProbeBody(val deps: List<AgentHostDepBody> = emptyList())

@Serializable
private data class AgentHostDepProbeItemResponse(
    val id: String = "",
    val present: Boolean = false,
    val detail: String = "",
)

@Serializable
private data class AgentHostDepsProbeResponse(
    val ok: Boolean = true,
    val items: List<AgentHostDepProbeItemResponse> = emptyList(),
)

@Serializable
private data class AgentHostDepsInstallBody(
    @SerialName("job_id") val jobId: String = "",
    val deps: List<AgentHostDepBody> = emptyList(),
)

@Serializable
private data class AgentHostDepsInstallResponse(
    val ok: Boolean = true,
    @SerialName("job_id") val jobId: String = "",
    val accepted: Boolean = false,
)

@Serializable
private data class AgentHostDepProgressItemResponse(
    val id: String = "",
    val status: String = "",
    val detail: String = "",
)

@Serializable
private data class AgentHostDepsProgressResponse(
    val ok: Boolean = true,
    @SerialName("job_id") val jobId: String = "",
    val phase: String = "",
    val detail: String = "",
    val pct: Int = 0,
    @SerialName("current_id") val currentId: String = "",
    val items: List<AgentHostDepProgressItemResponse> = emptyList(),
    val ready: Boolean = false,
    val failed: Boolean = false,
    val error: String = "",
    @SerialName("log_tail") val logTail: List<String> = emptyList(),
)

private fun HostDepSpec.toBody() = AgentHostDepBody(
    id = id,
    kind = kind,
    name = name,
    javaMajor = javaMajor,
)
