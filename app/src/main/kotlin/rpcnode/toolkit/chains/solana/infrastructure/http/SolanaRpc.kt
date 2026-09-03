package rpcnode.toolkit.chains.solana.infrastructure.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

private val json = Json { ignoreUnknownKeys = true }

/** Shared Solana JSON-RPC helpers (local height + public tip). */
object SolanaRpc
{
    fun getSlotRequest(): String =
        """{"jsonrpc":"2.0","id":1,"method":"getSlot","params":[]}"""

    fun parseSlot(body: String?): Long?
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
        val result = root["result"] ?: return null
        return when (result)
        {
            is JsonPrimitive -> result.contentOrNull?.toLongOrNull()
                ?: result.contentOrNull?.toDoubleOrNull()?.toLong()
            else -> result.jsonPrimitive.contentOrNull?.toLongOrNull()
        }
    }

    suspend fun getSlot(http: SimpleHttp, url: String): Long?
    {
        val endpoint = url.trim().trimEnd('/')
        if (endpoint.isEmpty())
        {
            return null
        }
        return parseSlot(http.postJson(endpoint, getSlotRequest()))
    }
}
