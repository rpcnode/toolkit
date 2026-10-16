package rpcnode.toolkit.chains.evmfamily.infrastructure.recipe

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Flare publishes no static bootstrap list: the IP and node id of its bootstrap host rotate and are read from the host's
 * own info API (`info.getNodeIP` / `info.getNodeID`, VERIFIED on flare-, songbird- and coston2-bootstrap.flare.network).
 * Looked up on every Start; the last good answer is kept in `.toolkit/flare-bootstrap.json` for when the host is down.
 */
object FlareBootstrap
{
    data class Peer(val ip: String, val nodeId: String)

    private val json = Json { ignoreUnknownKeys = true }
    private const val CACHE = ".toolkit/flare-bootstrap.json"

    fun infoUrl(host: String): String = "https://$host/ext/info"

    /** `{"result":{"ip":"34.79.60.151:9651"}}` → `34.79.60.151:9651` (the port is part of the value avalanchego wants). */
    fun parseIp(body: String?): String? =
        field(body, "ip")?.takeIf { Regex("""^[0-9a-fA-F.:\[\]]+:\d{2,5}$""").matches(it) }

    /** `{"result":{"nodeID":"NodeID-…"}}` → `NodeID-…`. */
    fun parseNodeId(body: String?): String? = field(body, "nodeID")?.takeIf { it.startsWith("NodeID-") && it.length > 10 }

    private fun field(body: String?, name: String): String? =
        runCatching { json.parseToJsonElement(body ?: return null).jsonObject["result"]?.jsonObject?.get(name)?.jsonPrimitive?.contentOrNull }
            .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    fun fetch(host: String): Peer?
    {
        val ip = call(host, "info.getNodeIP")?.let(::parseIp) ?: return null
        val id = call(host, "info.getNodeID")?.let(::parseNodeId) ?: return null
        return Peer(ip, id)
    }

    private fun call(host: String, method: String): String? = runCatching {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
        val req = HttpRequest.newBuilder(URI(infoUrl(host)))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"$method"}"""))
            .build()
        val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
        resp.body().takeIf { resp.statusCode() in 200..299 }
    }.getOrNull()

    /** Fresh lookup, else the cached answer; null when neither exists. */
    fun resolve(nodeDir: Path, host: String, lookup: (String) -> Peer? = ::fetch): Peer?
    {
        val cache = nodeDir.resolve(CACHE)
        val fresh = lookup(host)
        if (fresh != null)
        {
            runCatching {
                Files.createDirectories(cache.parent)
                Files.writeString(cache, "${fresh.ip}\n${fresh.nodeId}\n")
            }
            return fresh
        }
        val lines = runCatching { Files.readAllLines(cache) }.getOrNull() ?: return null
        return if (lines.size >= 2 && parseCached(lines[0], lines[1])) Peer(lines[0].trim(), lines[1].trim()) else null
    }

    private fun parseCached(ip: String, id: String) = ip.isNotBlank() && id.trim().startsWith("NodeID-")
}
