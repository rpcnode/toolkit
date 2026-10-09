package rpcnode.toolkit.chains.base.infrastructure.http

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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rpcnode.toolkit.chains.base.infrastructure.BaseClusters

/** Live tip from `chain.base.org/api/snapshots` (archive profile used for CDN VERSION match). */
fun interface BaseSnapshotTipProbe
{
    suspend fun tip(env: String, profile: String): Tip?

    data class Tip(
        val version: String,
        val manifestUrl: String,
        val sizeBytes: Long?,
    )
}

class HttpBaseSnapshotTipProbe(
    private val apiUrl: String = BaseClusters.SNAPSHOT_API_URL,
    private val timeout: Duration = Duration.ofSeconds(20),
) : BaseSnapshotTipProbe
{
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun tip(env: String, profile: String): BaseSnapshotTipProbe.Tip? =
        withContext(Dispatchers.IO) {
            val body = getText(apiUrl) ?: return@withContext null
            parseTip(body, env = env, profile = profile)
        }

    fun parseTip(body: String, env: String, profile: String): BaseSnapshotTipProbe.Tip?
    {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonArray
            ?: return null
        val wantNet = BaseClusters.lookup(env).env
        val wantProfile = profile.trim().lowercase().ifBlank { "archive" }
        for (el in root)
        {
            val obj = el as? JsonObject ?: continue
            val net = obj["network"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            val prof = obj["profile"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            if (net != wantNet || prof != wantProfile)
            {
                continue
            }
            val manifestUrl = obj["manifestUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (manifestUrl.isEmpty())
            {
                continue
            }
            val version = versionFromManifestUrl(manifestUrl)
                ?: obj["timestamp"]?.jsonPrimitive?.longOrNull?.toString()
                ?: continue
            val size = obj["size"]?.jsonPrimitive?.longOrNull
            return BaseSnapshotTipProbe.Tip(
                version = version,
                manifestUrl = manifestUrl,
                sizeBytes = size,
            )
        }
        return null
    }

    private fun versionFromManifestUrl(manifestUrl: String): String?
    {
        val path = try
        {
            URI(manifestUrl).path
        }
        catch (_: Exception)
        {
            return null
        }
        val parts = path?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        if (parts.size < 2)
        {
            return null
        }
        if (parts.last().equals("manifest.json", ignoreCase = true))
        {
            return parts[parts.lastIndex - 1]
        }
        return null
    }

    private fun getText(url: String): String?
    {
        return try
        {
            val client = HttpClient.newBuilder().connectTimeout(timeout).build()
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .header("User-Agent", "rpcnode-toolkit")
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200 until 300)
            {
                return null
            }
            resp.body()
        }
        catch (_: Exception)
        {
            null
        }
    }
}
