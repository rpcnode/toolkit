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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.snapshot.PollSnapshotOnHost
import rpcnode.toolkit.nodes.application.snapshot.ProbeSnapshotOnHost
import rpcnode.toolkit.nodes.application.snapshot.SnapshotHostProgress
import rpcnode.toolkit.nodes.application.snapshot.SnapshotHostSpeedResult
import rpcnode.toolkit.nodes.application.snapshot.SnapshotHostSpeedSample
import rpcnode.toolkit.nodes.application.snapshot.SnapshotHostStartCommand
import rpcnode.toolkit.nodes.application.snapshot.StartSnapshotOnHost
import rpcnode.toolkit.nodes.application.snapshot.StopSnapshotOnHost

class HttpSnapshotHostClient(
    private val timeout: Duration = Duration.ofSeconds(15),
    private val startTimeout: Duration = Duration.ofSeconds(30),
    private val probeTimeout: Duration = Duration.ofSeconds(60),
) : StartSnapshotOnHost, PollSnapshotOnHost, StopSnapshotOnHost, ProbeSnapshotOnHost
{
    private val log = LoggerFactory.getLogger(HttpSnapshotHostClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .build()

    override suspend fun start(
        agentUrl: String,
        token: String,
        command: SnapshotHostStartCommand,
    ): Boolean? = withContext(Dispatchers.IO) {
        try
        {
            val body = json.encodeToString(
                SnapshotStartPayload(
                    jobId = command.jobId,
                    url = command.url,
                    destDir = command.destDir,
                    streamUnpack = command.streamUnpack,
                    sizeBytes = command.sizeBytes,
                ),
            )
            val resp = post(agentUrl, "/api/v1/snapshot/start", token, body, startTimeout)
            when (resp.statusCode())
            {
                in 200 until 300 -> true
                409 -> false
                else ->
                {
                    log.warn("host agent {} snapshot/start: HTTP {}", agentUrl, resp.statusCode())
                    false
                }
            }
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host agent {} snapshot/start: {}", agentUrl, reason)
            null
        }
    }

    override suspend fun progress(
        agentUrl: String,
        token: String,
        jobId: String,
    ): SnapshotHostProgress? = withContext(Dispatchers.IO) {
        try
        {
            val q = URLEncoder.encode(jobId, StandardCharsets.UTF_8)
            val resp = get(agentUrl, "/api/v1/snapshot/progress?job_id=$q", token)
            if (resp.statusCode() !in 200 until 300)
            {
                log.warn("host agent {} snapshot/progress: HTTP {}", agentUrl, resp.statusCode())
                return@withContext null
            }
            val obj = json.parseToJsonElement(resp.body()).jsonObject
            SnapshotHostProgress(
                ok = obj["ok"]?.jsonPrimitive?.contentOrNull != "false",
                pct = obj["pct"]?.jsonPrimitive?.doubleOrNull,
                phase = obj["phase"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                detail = obj["detail"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ready = obj["ready"]?.jsonPrimitive?.contentOrNull == "true",
                failed = obj["failed"]?.jsonPrimitive?.contentOrNull == "true",
                error = obj["error"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                logTail = obj["log_tail"]?.jsonArray?.mapNotNull { el ->
                    el.jsonPrimitive.contentOrNull
                }.orEmpty(),
            )
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host agent {} snapshot/progress: {}", agentUrl, reason)
            null
        }
    }

    override suspend fun stop(
        agentUrl: String,
        token: String,
        jobId: String,
        wipeDest: Boolean,
    ): Boolean? = withContext(Dispatchers.IO) {
        try
        {
            val body = json.encodeToString(
                SnapshotStopPayload(jobId = jobId, wipeDest = wipeDest),
            )
            val resp = post(agentUrl, "/api/v1/snapshot/stop", token, body, timeout)
            if (resp.statusCode() !in 200 until 300)
            {
                log.warn("host agent {} snapshot/stop: HTTP {}", agentUrl, resp.statusCode())
                return@withContext false
            }
            true
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host agent {} snapshot/stop: {}", agentUrl, reason)
            null
        }
    }

    override suspend fun probe(
        agentUrl: String,
        token: String,
        samples: List<SnapshotHostSpeedSample>,
    ): List<SnapshotHostSpeedResult>? = withContext(Dispatchers.IO) {
        if (samples.isEmpty())
        {
            return@withContext emptyList()
        }
        try
        {
            val body = json.encodeToString(
                SnapshotProbePayload(
                    samples = samples.map { SnapshotProbeSamplePayload(id = it.id, url = it.url) },
                ),
            )
            val resp = post(agentUrl, "/api/v1/snapshot/probe", token, body, probeTimeout)
            if (resp.statusCode() !in 200 until 300)
            {
                log.warn("host agent {} snapshot/probe: HTTP {}", agentUrl, resp.statusCode())
                return@withContext null
            }
            val obj = json.parseToJsonElement(resp.body()).jsonObject
            val items = obj["results"]?.let { json.decodeFromJsonElement<List<SnapshotHostSpeedResultPayload>>(it) }
                ?: return@withContext emptyList()
            items.map {
                SnapshotHostSpeedResult(
                    id = it.id,
                    available = it.available,
                    bytesPerSec = it.bytesPerSec,
                    sampleBytes = it.sampleBytes,
                    latencyMs = it.latencyMs,
                    detail = it.detail,
                )
            }
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host agent {} snapshot/probe: {}", agentUrl, reason)
            null
        }
    }

    private fun get(agentUrl: String, path: String, token: String): HttpResponse<String>
    {
        val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + path))
            .timeout(timeout)
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }

    private fun post(
        agentUrl: String,
        path: String,
        token: String,
        body: String,
        timeout: Duration,
    ): HttpResponse<String>
    {
        val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + path))
            .timeout(timeout)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return client.send(req, HttpResponse.BodyHandlers.ofString())
    }
}

@Serializable
private data class SnapshotStartPayload(
    @SerialName("job_id") val jobId: String,
    val url: String,
    @SerialName("dest_dir") val destDir: String,
    @SerialName("stream_unpack") val streamUnpack: Boolean = false,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
)

@Serializable
private data class SnapshotStopPayload(
    @SerialName("job_id") val jobId: String,
    @SerialName("wipe_dest") val wipeDest: Boolean = true,
)

@Serializable
private data class SnapshotProbePayload(
    val samples: List<SnapshotProbeSamplePayload> = emptyList(),
)

@Serializable
private data class SnapshotProbeSamplePayload(
    val id: String,
    val url: String,
)

@Serializable
private data class SnapshotHostSpeedResultPayload(
    val id: String = "",
    val available: Boolean = false,
    @SerialName("bytes_per_sec") val bytesPerSec: Long? = null,
    @SerialName("sample_bytes") val sampleBytes: Long? = null,
    @SerialName("latency_ms") val latencyMs: Long? = null,
    val detail: String? = null,
)
