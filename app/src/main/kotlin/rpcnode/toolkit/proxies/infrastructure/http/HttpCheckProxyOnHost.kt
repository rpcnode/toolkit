package rpcnode.toolkit.proxies.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import rpcnode.toolkit.proxies.domain.model.CheckProxyOnHost
import rpcnode.toolkit.proxies.domain.model.ProxyCheck
import rpcnode.toolkit.proxies.domain.model.ProxyUrl

/** POST /api/v1/proxy/check on the host agent. */
class HttpCheckProxyOnHost(
    // two targets x 10 s on the agent, plus the hop
    private val timeout: Duration = Duration.ofSeconds(30),
) : CheckProxyOnHost
{
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override suspend fun check(agentUrl: String, token: String, url: ProxyUrl): ProxyCheck? = withContext(Dispatchers.IO) {
        try
        {
            val body = buildJsonObject { put("url", url.value) }.toString()
            val req = HttpRequest.newBuilder(URI(agentUrl.trimEnd('/') + "/api/v1/proxy/check"))
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() == 404)
            {
                return@withContext ProxyCheck.Failed("host agent is too old for proxy checks — update the agent")
            }
            val obj: JsonObject = runCatching { json.parseToJsonElement(resp.body()).jsonObject }.getOrNull()
                ?: return@withContext ProxyCheck.Failed("unexpected agent answer (HTTP ${resp.statusCode()})")
            if (obj["ok"]?.jsonPrimitive?.booleanOrNull == true)
            {
                ProxyCheck.Ok(
                    latencyMs = obj["latency_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                    target = obj["target"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
            else
            {
                ProxyCheck.Failed(
                    obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.statusCode()}",
                )
            }
        }
        catch (_: Exception)
        {
            null
        }
    }
}
