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
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.sysctl.HostSysctlReader
import rpcnode.toolkit.nodes.domain.model.HostSysctlCatalog
import rpcnode.toolkit.shared.infrastructure.log.HttpIoLog

class HttpHostSysctlReader(
    private val timeout: Duration = Duration.ofSeconds(15),
) : HostSysctlReader
{
    private val log = LoggerFactory.getLogger(HttpHostSysctlReader::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun read(agentUrl: String, token: String): HostSysctlCatalog? = withContext(Dispatchers.IO) {
        try
        {
            val url = "${agentUrl.trimEnd('/')}/api/v1/host/sysctl"
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
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            HttpIoLog.outbound("GET", url, resp.statusCode(), (System.nanoTime() - started) / 1_000_000)
            if (resp.statusCode() !in 200 until 300)
            {
                return@withContext null
            }
            val body = json.decodeFromString(AgentHostSysctlPayload.serializer(), resp.body())
            HostSysctlCatalog(
                current = body.current,
                recommended = body.recommended,
                installOptionKeys = body.installOptionKeys,
            )
        }
        catch (e: Exception)
        {
            val reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            log.warn("host sysctl {}: {}", agentUrl, reason)
            null
        }
    }
}

@Serializable
private data class AgentHostSysctlPayload(
    val ok: Boolean = true,
    val current: Map<String, Long?> = emptyMap(),
    val recommended: Map<String, Long> = emptyMap(),
    @SerialName("install_option_keys") val installOptionKeys: Map<String, String> = emptyMap(),
)
