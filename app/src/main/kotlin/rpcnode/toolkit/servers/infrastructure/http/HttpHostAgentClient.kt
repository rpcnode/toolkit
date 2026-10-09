package rpcnode.toolkit.servers.infrastructure.http

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
import rpcnode.toolkit.servers.application.probe.AGENT_METRICS_INGEST_PATH
import rpcnode.toolkit.servers.application.probe.AgentPortCheck
import rpcnode.toolkit.servers.application.probe.CheckAgentPorts
import rpcnode.toolkit.servers.application.probe.EnrollHostAgent
import rpcnode.toolkit.servers.application.probe.EnrollHostAgentResult
import rpcnode.toolkit.servers.application.probe.HostAgentClient
import rpcnode.toolkit.servers.application.probe.HostAgentIdentity
import rpcnode.toolkit.servers.application.probe.HostAgentLookup
import rpcnode.toolkit.servers.application.probe.HostAgentUpdate
import rpcnode.toolkit.servers.application.probe.UnenrollHostAgent
import rpcnode.toolkit.servers.application.probe.UpdateHostAgent
import rpcnode.toolkit.shared.infrastructure.log.HttpIoLog

class HttpHostAgentClient(
    private val timeout: Duration = Duration.ofSeconds(5),
    private val enrollTimeout: Duration = Duration.ofSeconds(15),
    private val updateTimeout: Duration = Duration.ofSeconds(60),
) : HostAgentClient, EnrollHostAgent, UnenrollHostAgent, UpdateHostAgent, CheckAgentPorts
{
    private val log = LoggerFactory.getLogger(HttpHostAgentClient::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun identity(agentUrl: String, token: String): HostAgentIdentity? =
        when (val got = lookup(agentUrl, token))
        {
            is HostAgentLookup.Ok -> got.identity
            else -> null
        }

    override suspend fun lookup(agentUrl: String, token: String): HostAgentLookup = withContext(Dispatchers.IO) {
        try
        {
            val resp = get(agentUrl, "/api/v1/agent", token)
            when
            {
                resp.status in 200 until 300 ->
                {
                    val body = resp.body
                    if (body.isBlank())
                    {
                        return@withContext HostAgentLookup.Failed("empty response")
                    }
                    val obj = json.parseToJsonElement(body).jsonObject
                    HostAgentLookup.Ok(
                        HostAgentIdentity(
                            version = obj["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            os = obj["os"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            arch = obj["arch"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            osPretty = obj["os_pretty"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        ),
                    )
                }
                resp.status == 401 || resp.status == 403 -> HostAgentLookup.Unauthorized(resp.status)
                else -> HostAgentLookup.Failed("HTTP ${resp.status}")
            }
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host agent {}: {}", agentUrl, reason)
            HostAgentLookup.Failed(reason)
        }
    }

    override suspend fun enroll(agentUrl: String, token: String, panelUrl: String, serverId: String): EnrollHostAgentResult =
        withContext(Dispatchers.IO) {
            try
            {
                val payload = json.encodeToString(
                    EnrollBody(
                        panelUrl = panelUrl.trim().trimEnd('/'),
                        serverId = serverId,
                        ingestPath = AGENT_METRICS_INGEST_PATH,
                    ),
                )
                val resp = post(agentUrl, "/api/v1/enroll", token, payload, enrollTimeout)
                when
                {
                    resp.statusCode() in 200 until 300 -> EnrollHostAgentResult.Ok
                    resp.statusCode() == 401 || resp.statusCode() == 403 ->
                        EnrollHostAgentResult.Unauthorized
                    agentError(resp.body()) == "panel_unreachable" -> EnrollHostAgentResult.PanelUnreachable
                    agentError(resp.body()) == "unauthorized" || agentError(resp.body()) == "token_required" ->
                        EnrollHostAgentResult.Unauthorized
                    else ->
                    {
                        val reason = enrollFailure(resp.statusCode(), resp.body())
                        log.warn("host agent {}/api/v1/enroll: {}", agentUrl, reason)
                        EnrollHostAgentResult.Failed(reason)
                    }
                }
            }
            catch (e: Exception)
            {
                val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
                log.warn("host agent {} enroll: {}", agentUrl, reason)
                EnrollHostAgentResult.Failed(reason)
            }
        }

    override suspend fun unenroll(agentUrl: String, token: String): Boolean = withContext(Dispatchers.IO) {
        try
        {
            request(agentUrl, "/api/v1/unenroll", token, "{}") != null
        }
        catch (e: Exception)
        {
            log.warn("host agent {} unenroll: {}", agentUrl, e.message)
            false
        }
    }

    override suspend fun update(agentUrl: String, token: String, force: Boolean): HostAgentUpdate? =
        withContext(Dispatchers.IO) {
            try
            {
                val payload = json.encodeToString(UpdateBody(force = force))
                val resp = post(agentUrl, "/api/v1/agent/update", token, payload, updateTimeout)
                val obj = if (resp.body().isBlank())
                {
                    kotlinx.serialization.json.JsonObject(emptyMap())
                }
                else
                {
                    json.parseToJsonElement(resp.body()).jsonObject
                }
                val okFlag = obj["ok"]?.jsonPrimitive?.contentOrNull != "false"
                HostAgentUpdate(
                    ok = resp.statusCode() in 200 until 300 && okFlag,
                    updated = obj["updated"]?.jsonPrimitive?.contentOrNull == "true",
                    version = obj["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    remoteVersion = obj["remote_version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    error = obj["error"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    status = resp.statusCode(),
                )
            }
            catch (e: Exception)
            {
                log.warn("host agent {} update: {}", agentUrl, e.message)
                null
            }
        }

    override suspend fun checkPorts(agentUrl: String, token: String, ports: List<Int>): List<AgentPortCheck>? =
        withContext(Dispatchers.IO) {
            try
            {
                val payload = json.encodeToString(PortsCheckBody(ports = ports))
                val resp = post(agentUrl, "/api/v1/agent/ports/check", token, payload, timeout)
                if (resp.statusCode() !in 200 until 300)
                {
                    log.warn("host agent {} ports/check: HTTP {}", agentUrl, resp.statusCode())
                    return@withContext null
                }
                val obj = json.parseToJsonElement(resp.body()).jsonObject
                obj["items"]?.jsonArray?.map {
                    val item = it.jsonObject
                    AgentPortCheck(
                        port = item["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                        free = item["free"]?.jsonPrimitive?.contentOrNull == "true",
                        holder = item["holder"]?.jsonPrimitive?.contentOrNull,
                    )
                }.orEmpty()
            }
            catch (e: Exception)
            {
                val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
                log.warn("host agent {} ports/check: {}", agentUrl, reason)
                null
            }
        }

    private data class RawGet(val status: Int, val body: String)

    private fun get(agentUrl: String, path: String, token: String): RawGet
    {
        val url = "${agentUrl.trimEnd('/')}$path"
        val started = System.nanoTime()
        val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(timeout)
            .build()
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        return try
        {
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            HttpIoLog.outbound("GET", url, resp.statusCode(), elapsedMs(started))
            RawGet(resp.statusCode(), resp.body().orEmpty())
        }
        catch (e: Exception)
        {
            HttpIoLog.outbound("GET", url, 0, elapsedMs(started), e.message)
            throw e
        }
    }

    private fun request(agentUrl: String, path: String, token: String, jsonBody: String?): kotlinx.serialization.json.JsonObject?
    {
        val url = "${agentUrl.trimEnd('/')}$path"
        val method = if (jsonBody != null) "POST" else "GET"
        val started = System.nanoTime()
        return try
        {
            val client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build()
            val builder = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
            val req = if (jsonBody != null)
            {
                builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build()
            }
            else
            {
                builder.GET().build()
            }
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            HttpIoLog.outbound(method, url, resp.statusCode(), elapsedMs(started))
            if (resp.statusCode() !in 200 until 300)
            {
                return null
            }
            if (resp.body().isBlank())
            {
                return kotlinx.serialization.json.JsonObject(emptyMap())
            }
            json.parseToJsonElement(resp.body()).jsonObject
        }
        catch (e: Exception)
        {
            HttpIoLog.outbound(method, url, 0, elapsedMs(started), e.message)
            throw e
        }
    }

    private fun post(
        agentUrl: String,
        path: String,
        token: String,
        jsonBody: String,
        callTimeout: Duration,
    ): HttpResponse<String>
    {
        val url = "${agentUrl.trimEnd('/')}$path"
        val started = System.nanoTime()
        return try
        {
            val client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(callTimeout)
                .build()
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(callTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            HttpIoLog.outbound("POST", url, resp.statusCode(), elapsedMs(started))
            resp
        }
        catch (e: Exception)
        {
            HttpIoLog.outbound("POST", url, 0, elapsedMs(started), e.message)
            throw e
        }
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000

    private fun enrollFailure(status: Int, body: String): String
    {
        val err = agentError(body)
        val extra = agentMessage(body)
        return buildString {
            append("HTTP $status")
            if (err.isNotEmpty())
            {
                append(" ($err)")
            }
            if (extra.isNotEmpty() && extra != err)
            {
                append(": $extra")
            }
        }
    }

    private fun agentMessage(body: String): String
    {
        if (body.isBlank())
        {
            return ""
        }
        return runCatching {
            json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.getOrDefault("")
    }

    private fun agentError(body: String): String
    {
        if (body.isBlank())
        {
            return ""
        }
        return runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }.getOrDefault("")
    }
}

@Serializable
private data class UpdateBody(
    val force: Boolean = false,
)

@Serializable
private data class PortsCheckBody(
    val ports: List<Int> = emptyList(),
)

@Serializable
private data class EnrollBody(
    @SerialName("panel_url") val panelUrl: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("ingest_path") val ingestPath: String,
)
