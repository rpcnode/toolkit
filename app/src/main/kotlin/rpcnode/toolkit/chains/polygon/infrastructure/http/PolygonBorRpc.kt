package rpcnode.toolkit.chains.polygon.infrastructure.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

private val json = Json { ignoreUnknownKeys = true }

/** Shared bor JSON-RPC helpers (local height + public tip). */
object PolygonBorRpc
{
    fun blockNumberRequest(): String =
        """{"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}"""

    fun parseBlockNumber(body: String?): Long?
    {
        if (body.isNullOrBlank())
        {
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val err = root["error"]
        if (err != null && err !is JsonPrimitive)
        {
            return null
        }
        val hex = root["result"]?.jsonPrimitive?.contentOrNull?.trim() ?: return null
        return parseHexInt64(hex)
    }

    fun parseHexInt64(hex: String): Long?
    {
        val raw = hex.trim().removePrefix("0x").removePrefix("0X")
        if (raw.isEmpty())
        {
            return 0L
        }
        return raw.toLongOrNull(16)
    }

    suspend fun blockNumber(http: SimpleHttp, url: String): Long?
    {
        val endpoint = url.trim().trimEnd('/')
        if (endpoint.isEmpty())
        {
            return null
        }
        return parseBlockNumber(http.postJson(endpoint, blockNumberRequest()))
    }
}
