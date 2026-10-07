package rpcnode.toolkit.nodes.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.nodeconfig.HostFileRead
import rpcnode.toolkit.nodes.application.nodeconfig.HostFileWrite
import rpcnode.toolkit.nodes.application.nodeconfig.HostFiles

/** POST /api/v1/files/read and /api/v1/files/write on the host agent. */
class HttpHostFiles(
    private val timeout: Duration = Duration.ofSeconds(20),
) : HostFiles
{
    private val log = LoggerFactory.getLogger(HttpHostFiles::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override suspend fun read(agentUrl: String, token: String, path: String): HostFileRead? =
        withContext(Dispatchers.IO) {
            val resp = post(agentUrl, token, "/api/v1/files/read", json.encodeToString(PathBody(path))) ?: return@withContext null
            if (resp.statusCode() == 401) return@withContext HostFileRead.Unauthorized
            val parsed = runCatching { json.decodeFromString(ReadResponse.serializer(), resp.body()) }.getOrNull()
            when
            {
                resp.statusCode() == 413 || parsed?.error == "too_large" -> HostFileRead.TooLarge
                resp.statusCode() == 404 || parsed == null ->
                    HostFileRead.Failed("agent does not support file read (HTTP ${resp.statusCode()}) — update the agent")
                !parsed.ok -> HostFileRead.Failed(parsed.message?.ifBlank { null } ?: parsed.error?.ifBlank { null } ?: "read failed")
                else -> HostFileRead.Ok(path = parsed.path?.ifBlank { null } ?: path, exists = parsed.exists, content = parsed.content)
            }
        }

    override suspend fun write(agentUrl: String, token: String, path: String, content: String): HostFileWrite? =
        withContext(Dispatchers.IO) {
            val resp = post(agentUrl, token, "/api/v1/files/write", json.encodeToString(WriteBody(path, content)))
                ?: return@withContext null
            if (resp.statusCode() == 401) return@withContext HostFileWrite.Unauthorized
            val parsed = runCatching { json.decodeFromString(WriteResponse.serializer(), resp.body()) }.getOrNull()
            if (resp.statusCode() !in 200 until 300 || parsed == null || !parsed.ok)
            {
                return@withContext HostFileWrite.Failed(
                    parsed?.message?.ifBlank { null } ?: parsed?.error?.ifBlank { null } ?: "write failed (HTTP ${resp.statusCode()})",
                )
            }
            HostFileWrite.Ok(parsed.path?.ifBlank { null } ?: path)
        }

    private fun post(agentUrl: String, token: String, path: String, body: String): HttpResponse<String>? =
        try
        {
            val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + path))
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            client.send(req, HttpResponse.BodyHandlers.ofString())
        }
        catch (e: Exception)
        {
            log.warn("host agent {} {}: {}", agentUrl, path, e.message?.ifBlank { null } ?: e.javaClass.simpleName)
            null
        }
}

@Serializable
private data class PathBody(val path: String)

@Serializable
private data class WriteBody(val path: String, val content: String)

@Serializable
private data class ReadResponse(
    val ok: Boolean = true,
    val path: String? = null,
    val exists: Boolean = false,
    val content: String = "",
    val error: String? = null,
    val message: String? = null,
)

@Serializable
private data class WriteResponse(
    val ok: Boolean = true,
    val path: String? = null,
    val error: String? = null,
    val message: String? = null,
)
