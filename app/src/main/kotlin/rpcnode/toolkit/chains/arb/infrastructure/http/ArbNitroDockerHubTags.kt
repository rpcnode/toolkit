package rpcnode.toolkit.chains.arb.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.chains.arb.infrastructure.docker.ArbNitroDockerTags

/** Lists Docker Hub tags for `offchainlabs/nitro-node` filtered by name prefix. */
fun interface ArbNitroDockerHubTags
{
    suspend fun listMatching(namePrefix: String): List<String>
}

class HttpArbNitroDockerHubTags(
    private val timeout: Duration = Duration.ofSeconds(15),
    private val hubBase: String = "https://hub.docker.com/v2",
) : ArbNitroDockerHubTags
{
    override suspend fun listMatching(namePrefix: String): List<String> = withContext(Dispatchers.IO) {
        val prefix = namePrefix.trim()
        if (prefix.isEmpty())
        {
            return@withContext emptyList()
        }
        val client = HttpClient.newBuilder().connectTimeout(timeout).build()
        val uri = URI(
            "$hubBase/repositories/${ArbNitroDockerTags.IMAGE}/tags" +
                "?page_size=100&name=${java.net.URLEncoder.encode(prefix, Charsets.UTF_8)}",
        )
        val req = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Accept", "application/json")
            .header("User-Agent", "rpcnode-server")
            .GET()
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200 until 300)
        {
            return@withContext emptyList()
        }
        val root = Json.parseToJsonElement(resp.body()).jsonObject
        root["results"]?.jsonArray.orEmpty().mapNotNull { el ->
            el.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
        }
    }
}

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): kotlinx.serialization.json.JsonArray =
    this ?: kotlinx.serialization.json.JsonArray(emptyList())
