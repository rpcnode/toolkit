package rpcnode.toolkit.chains.hyperliquid.infrastructure

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolves gossip `root_node_ips` for non-validator start (blocking — host Start path).
 * Prefers live peer lists; falls back to [HyperliquidCluster.seedPeers].
 * Empty list is fatal — hl-node panics on empty gossip_config.
 */
object HyperliquidGossipPeers
{
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .build()

    fun resolve(cluster: HyperliquidCluster): List<String>
    {
        val live = when (cluster.env)
        {
            "testnet" ->
            {
                fetchGet("https://hyperliquid-testnet.imperator.co/peers.json")
                    .ifEmpty { fetchGet("https://hyperliquid-peers.all4nodes.io/") }
            }
            else ->
            {
                fetchPost("https://api.hyperliquid.xyz/info", """{"type":"gossipRootIps"}""")
            }
        }
        return dedupe(live + cluster.seedPeers)
    }

    fun parseBody(body: String?): List<String>
    {
        if (body.isNullOrBlank())
        {
            return emptyList()
        }
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        if (element is JsonArray)
        {
            return dedupe(
                element.mapNotNull { el ->
                    (el as? JsonPrimitive)?.contentOrNull
                },
            )
        }
        val obj = element as? JsonObject ?: return emptyList()
        val roots = obj["root_node_ips"]?.jsonArray ?: return emptyList()
        return dedupe(
            roots.mapNotNull { entry ->
                val o = entry as? JsonObject ?: return@mapNotNull null
                o["Ip"]?.jsonPrimitive?.contentOrNull
                    ?: o["ip"]?.jsonPrimitive?.contentOrNull
            },
        )
    }

    private fun fetchGet(url: String): List<String> =
        try
        {
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (resp.statusCode() in 200..299) parseBody(resp.body()) else emptyList()
        }
        catch (_: Exception)
        {
            emptyList()
        }

    private fun fetchPost(url: String, body: String): List<String> =
        try
        {
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (resp.statusCode() in 200..299) parseBody(resp.body()) else emptyList()
        }
        catch (_: Exception)
        {
            emptyList()
        }

    private fun dedupe(inList: List<String>): List<String>
    {
        val seen = linkedSetOf<String>()
        for (raw in inList)
        {
            val s = raw.trim()
            if (s.isNotEmpty())
            {
                seen += s
            }
        }
        return seen.toList()
    }
}
