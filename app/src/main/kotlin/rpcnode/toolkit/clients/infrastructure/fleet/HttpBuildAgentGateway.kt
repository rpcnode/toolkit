package rpcnode.toolkit.clients.infrastructure.fleet

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import rpcnode.toolkit.clients.application.build.BuildAgentGateway
import rpcnode.toolkit.clients.application.build.BuildHost
import rpcnode.toolkit.clients.application.build.BuildJobProgress
import rpcnode.toolkit.clients.application.build.BuildJobRequest
import rpcnode.toolkit.clients.application.build.BuildJobStart

@Serializable
private data class StartBody(
    @SerialName("job_id") val jobId: String,
    val network: String,
    val program: String,
    val version: String,
    val tag: String,
    val platform: String,
    val dockerfile: String,
    val outputs: List<String>,
)

@Serializable
private data class StartAnswer(val ok: Boolean = true, val error: String = "", val message: String = "")

@Serializable
private data class ProgressAnswer(
    val ok: Boolean = true,
    val phase: String = "unknown",
    val detail: String = "",
    @SerialName("log_tail") val logTail: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    @SerialName("log_file") val logFile: String = "",
)

class HttpBuildAgentGateway(
    private val timeout: Duration = Duration.ofSeconds(20),
    private val downloadTimeout: Duration = Duration.ofMinutes(30),
) : BuildAgentGateway
{
    private val log = LoggerFactory.getLogger(HttpBuildAgentGateway::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build()

    override suspend fun start(host: BuildHost, request: BuildJobRequest): BuildJobStart = withContext(Dispatchers.IO) {
        try
        {
            val body = json.encodeToString(
                StartBody(request.jobId, request.network, request.program, request.version, request.tag, request.platform, request.dockerfile, request.outputs),
            )
            val resp = http.send(
                builder(host, "/api/v1/client/build").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val answer = runCatching { json.decodeFromString<StartAnswer>(resp.body()) }.getOrDefault(StartAnswer())
            val detail = answer.message.ifBlank { answer.error }.ifBlank { "HTTP ${resp.statusCode()}" }
            when
            {
                resp.statusCode() in 200 until 300 -> BuildJobStart.Accepted
                resp.statusCode() == 409 -> BuildJobStart.Busy(detail)
                // 404/405: an agent that predates the build protocol — treat like a host that cannot build.
                resp.statusCode() == 404 || resp.statusCode() == 405 ->
                    BuildJobStart.Unavailable("agent does not support client builds yet (update the agent)")
                resp.statusCode() == 503 || resp.statusCode() == 422 -> BuildJobStart.Unavailable(detail)
                else -> BuildJobStart.Failed(detail)
            }
        }
        catch (e: Exception)
        {
            log.warn("build agent {} start: {}", host.agentUrl, e.message)
            BuildJobStart.Unavailable(e.message?.ifBlank { null } ?: e.javaClass.simpleName)
        }
    }

    override suspend fun progress(host: BuildHost, jobId: String): BuildJobProgress? = withContext(Dispatchers.IO) {
        try
        {
            val resp = http.send(
                builder(host, "/api/v1/client/build/progress?job_id=${enc(jobId)}").GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (resp.statusCode() !in 200 until 300)
            {
                return@withContext null
            }
            val a = json.decodeFromString<ProgressAnswer>(resp.body())
            BuildJobProgress(a.phase, a.detail, a.logTail, a.files, a.logFile)
        }
        catch (e: Exception)
        {
            null
        }
    }

    override suspend fun download(host: BuildHost, jobId: String, name: String, dest: Path)
    {
        withContext(Dispatchers.IO) {
            val resp = http.send(
                builder(host, "/api/v1/client/build/artifact?job_id=${enc(jobId)}&name=${enc(name)}", downloadTimeout)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            resp.body().use { input ->
                if (resp.statusCode() !in 200 until 300)
                {
                    error("HTTP ${resp.statusCode()} fetching $name")
                }
                Files.createDirectories(dest.parent)
                Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
            }
            if (Files.size(dest) <= 0) error("$name arrived empty")
        }
    }

    override suspend fun release(host: BuildHost, jobId: String)
    {
        withContext(Dispatchers.IO) {
            http.send(
                builder(host, "/api/v1/client/build/release").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"job_id":"$jobId"}""")).build(),
                HttpResponse.BodyHandlers.discarding(),
            )
        }
    }

    private fun builder(host: BuildHost, path: String, callTimeout: Duration = timeout): HttpRequest.Builder =
        HttpRequest.newBuilder(URI("${host.agentUrl.trimEnd('/')}$path"))
            .timeout(callTimeout)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer ${host.token}")

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
