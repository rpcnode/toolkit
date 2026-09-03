package rpcnode.toolkit.cdn.infrastructure.http

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Base V2 modular snapshot helpers: segment path collection and `base_url` rewrite.
 * Relative paths under [base_url] are preserved; only the origin changes.
 */
object BaseManifestMirror
{
    private val json = Json { ignoreUnknownKeys = true }

    /** Rewrite `base_url` so `base-reth-node download --manifest-url` pulls from the CDN. */
    fun rewriteBaseUrl(manifestJson: String, publicBaseUrl: String): String
    {
        val root = json.parseToJsonElement(manifestJson).jsonObject.toMutableMap()
        val origin = publicBaseUrl.trim().trimEnd('/')
        root["base_url"] = JsonPrimitive(origin)
        return json.encodeToString(JsonObject.serializer(), JsonObject(root))
    }

    /**
     * Relative segment paths under the upstream `base_url` (e.g. `static_files/…`,
     * `{version}/state.tar.zst`). Does not include `manifest.json`.
     */
    fun segmentRelativePaths(manifestJson: String): List<String>
    {
        val root = json.parseToJsonElement(manifestJson).jsonObject
        val components = root["components"]?.jsonObject ?: return emptyList()
        val out = linkedSetOf<String>()
        for ((_, value) in components)
        {
            val comp = value as? JsonObject ?: continue
            comp["file"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let {
                out += it.trimStart('/')
            }
            val chunks = comp["chunk_files"] as? JsonArray ?: continue
            for (item in chunks)
            {
                val path = item.jsonPrimitive.contentOrNull?.trim().orEmpty()
                if (path.isNotEmpty())
                {
                    out += path.trimStart('/')
                }
            }
        }
        return out.toList()
    }

    fun joinUrl(baseUrl: String, relativePath: String): String
    {
        val base = baseUrl.trim().trimEnd('/')
        val rel = relativePath.trim().trimStart('/')
        return "$base/$rel"
    }

    fun upstreamBaseUrl(manifestJson: String): String?
    {
        val root = json.parseToJsonElement(manifestJson).jsonObject
        return root["base_url"]?.jsonPrimitive?.contentOrNull?.trim()?.trimEnd('/')
    }

    fun versionFromManifestUrl(manifestUrl: String): String?
    {
        val path = try
        {
            URI(manifestUrl.trim()).path
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
        val last = parts.last()
        if (last.equals("manifest.json", ignoreCase = true))
        {
            return parts[parts.lastIndex - 1]
        }
        return null
    }
}
