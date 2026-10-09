package rpcnode.toolkit.chains.arb.infrastructure.docker

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Anonymous Docker Hub Registry V2 client — pull manifests/layers over HTTPS without a Docker daemon.
 */
class ArbNitroRegistryClient(
    private val timeout: Duration = Duration.ofMinutes(30),
    private val authBase: String = "https://auth.docker.io",
    private val registryBase: String = "https://registry-1.docker.io",
)
{
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    data class Layer(
        val digest: String,
        val size: Long,
    )

    fun resolveLayers(repository: String, tag: String, architecture: String): List<Layer>
    {
        val token = pullToken(repository)
        val root = getManifestJson(repository, tag, token, MANIFEST_ACCEPT_LIST)
        val mediaType = root["mediaType"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val imageManifest = when
        {
            mediaType.contains("manifest.list") || mediaType.contains("image.index") ||
                root.containsKey("manifests") ->
            {
                val digest = pickPlatformDigest(root, architecture)
                    ?: throw IllegalStateException(
                        "no linux/$architecture manifest for $repository:$tag",
                    )
                getManifestJson(repository, digest, token, MANIFEST_ACCEPT_IMAGE)
            }
            else -> root
        }
        val layers = imageManifest["layers"]?.jsonArray
            ?: throw IllegalStateException("image manifest has no layers ($repository:$tag)")
        return layers.map { el ->
            val o = el.jsonObject
            val digest = o["digest"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(digest.startsWith("sha256:")) { "bad layer digest in $repository:$tag" }
            Layer(digest = digest, size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
    }

    fun downloadBlob(repository: String, digest: String, dest: Path)
    {
        val token = pullToken(repository)
        Files.createDirectories(dest.parent)
        val req = HttpRequest.newBuilder(URI("$registryBase/v2/$repository/blobs/$digest"))
            .timeout(timeout)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "rpcnode-server")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest))
        if (resp.statusCode() !in 200 until 300)
        {
            Files.deleteIfExists(dest)
            throw IllegalStateException(
                "registry blob $repository@$digest: HTTP ${resp.statusCode()}",
            )
        }
    }

    private fun pullToken(repository: String): String
    {
        val uri = URI(
            "$authBase/token?service=registry.docker.io&scope=repository:$repository:pull",
        )
        val req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "rpcnode-server")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200 until 300)
        {
            throw IllegalStateException("docker hub auth: HTTP ${resp.statusCode()}")
        }
        val token = json.parseToJsonElement(resp.body()).jsonObject["token"]
            ?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (token.isEmpty())
        {
            throw IllegalStateException("docker hub auth: empty token")
        }
        return token
    }

    private fun getManifestJson(
        repository: String,
        reference: String,
        token: String,
        accept: String,
    ): JsonObject
    {
        val req = HttpRequest.newBuilder(URI("$registryBase/v2/$repository/manifests/$reference"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $token")
            .header("Accept", accept)
            .header("User-Agent", "rpcnode-server")
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200 until 300)
        {
            throw IllegalStateException(
                "registry manifest $repository:$reference: HTTP ${resp.statusCode()}",
            )
        }
        return json.parseToJsonElement(resp.body()).jsonObject
    }

    private fun pickPlatformDigest(index: JsonObject, architecture: String): String?
    {
        val manifests = index["manifests"]?.jsonArray ?: return null
        val arch = architecture.lowercase()
        for (el in manifests)
        {
            val o = el.jsonObject
            val platform = o["platform"]?.jsonObject ?: continue
            val os = platform["os"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val a = platform["architecture"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            if (os == "linux" && a == arch)
            {
                return o["digest"]?.jsonPrimitive?.contentOrNull?.trim()
            }
        }
        return null
    }

    companion object
    {
        private const val MANIFEST_ACCEPT_LIST =
            "application/vnd.oci.image.index.v1+json," +
                "application/vnd.docker.distribution.manifest.list.v2+json," +
                "application/vnd.docker.distribution.manifest.v2+json," +
                "application/vnd.oci.image.manifest.v1+json"

        private const val MANIFEST_ACCEPT_IMAGE =
            "application/vnd.docker.distribution.manifest.v2+json," +
                "application/vnd.oci.image.manifest.v1+json"
    }
}
