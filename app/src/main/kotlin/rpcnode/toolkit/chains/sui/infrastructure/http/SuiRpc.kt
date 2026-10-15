package rpcnode.toolkit.chains.sui.infrastructure.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

private val json = Json { ignoreUnknownKeys = true }

/** Shared Sui helpers: local height (Prometheus, `/health` headers) and public tip (JSON-RPC, GraphQL). */
object SuiRpc
{
    fun latestCheckpointRequest(): String =
        """{"jsonrpc":"2.0","id":1,"method":"sui_getLatestCheckpointSequenceNumber","params":[]}"""

    fun parseCheckpoint(body: String?): Long?
    {
        if (body.isNullOrBlank())
        {
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val err = root["error"]
        if (err != null && err !is JsonPrimitive)
        {
            // Mysten public fullnode often returns method-not-found — treat as miss.
            return null
        }
        val result = root["result"] ?: return null
        return when (result)
        {
            is JsonPrimitive -> result.contentOrNull?.toLongOrNull()
                ?: result.contentOrNull?.toDoubleOrNull()?.toLong()
            else -> result.jsonPrimitive.contentOrNull?.toLongOrNull()
        }
    }

    suspend fun latestCheckpoint(http: SimpleHttp, url: String): Long?
    {
        val endpoint = url.trim().trimEnd('/')
        if (endpoint.isEmpty())
        {
            return null
        }
        return parseCheckpoint(http.postJson(endpoint, latestCheckpointRequest()))
    }

    /** sui-node puts the latest synced checkpoint in this header of every HTTP response (also `/health`). */
    const val CHECKPOINT_HEADER = "x-sui-checkpoint-height"

    fun parseCheckpointHeader(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

    /** Local node height from `GET /health` on the RPC port — the port itself speaks gRPC, so no JSON-RPC here. */
    suspend fun healthCheckpoint(http: SimpleHttp, rpcPort: Int): Long?
    {
        if (rpcPort <= 0)
        {
            return null
        }
        return parseCheckpointHeader(http.getHeader("http://127.0.0.1:$rpcPort/health", CHECKPOINT_HEADER))
    }

    fun parsePromSample(line: String): Pair<String, Long>?
    {
        val ln = line.trim()
        if (ln.isEmpty() || ln.startsWith("#"))
        {
            return null
        }
        val space = ln.lastIndexOf(' ')
        if (space <= 0)
        {
            return null
        }
        val left = ln.substring(0, space)
        val right = ln.substring(space + 1).trim()
        val value = right.toDoubleOrNull()?.toLong() ?: return null
        val name = left.substringBefore('{').trim()
        if (name.isEmpty())
        {
            return null
        }
        return name to value
    }

    fun parseSyncedCheckpoint(metricsBody: String?): Long?
    {
        if (metricsBody.isNullOrBlank())
        {
            return null
        }
        var synced: Long? = null
        var known: Long? = null
        for (line in metricsBody.lineSequence())
        {
            val sample = parsePromSample(line) ?: continue
            when (sample.first)
            {
                "highest_synced_checkpoint" -> synced = sample.second
                "highest_known_checkpoint", "highest_verified_checkpoint" ->
                    known = maxOf(known ?: Long.MIN_VALUE, sample.second)
            }
        }
        return synced ?: known?.takeIf { it >= 0 }
    }

    suspend fun scrapeSyncedCheckpoint(http: SimpleHttp, metricsPort: Int): Long?
    {
        if (metricsPort <= 0)
        {
            return null
        }
        val url = System.getenv("SUI_METRICS_URL")?.trim().orEmpty()
            .ifEmpty { "http://127.0.0.1:$metricsPort/metrics" }
        return parseSyncedCheckpoint(http.getText(url))
    }

    fun parseGraphQlCheckpoint(body: String?): Long?
    {
        if (body.isNullOrBlank())
        {
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root["errors"] != null && root["errors"] !is JsonPrimitive)
        {
            return null
        }
        val data = root["data"]?.jsonObject ?: return null
        val checkpoint = data["checkpoint"]?.jsonObject ?: return null
        val seq = checkpoint["sequenceNumber"] ?: return null
        return when (seq)
        {
            is JsonPrimitive -> seq.contentOrNull?.toLongOrNull()
                ?: seq.contentOrNull?.toDoubleOrNull()?.toLong()
            else -> seq.jsonPrimitive.contentOrNull?.toLongOrNull()
        }
    }

    suspend fun graphQlCheckpoint(http: SimpleHttp, url: String): Long?
    {
        val endpoint = url.trim()
        if (endpoint.isEmpty())
        {
            return null
        }
        val body = """{"query":"query { checkpoint { sequenceNumber } }"}"""
        return parseGraphQlCheckpoint(http.postJson(endpoint, body))
    }
}
