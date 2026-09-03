package rpcnode.toolkit.chains.ton.infrastructure.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

private val json = Json { ignoreUnknownKeys = true }

/**
 * Shared `getMasterchainInfo` fetch + parse for local THA and toncenter tip.
 * Returns masterchain seqno or null.
 */
object TonGetMasterchainInfo
{
    suspend fun seqno(http: SimpleHttp, baseUrl: String): Long?
    {
        val root = baseUrl.trim().trimEnd('/')
        if (root.isEmpty())
        {
            return null
        }
        val candidates = listOf(
            if (root.endsWith("/getMasterchainInfo")) root else "$root/getMasterchainInfo",
            if (root.contains("/api/v2/")) root else "$root/api/v2/getMasterchainInfo",
        ).distinct()
        for (url in candidates)
        {
            val body = http.getText(url) ?: continue
            val n = parseSeqno(body) ?: continue
            if (n > 0)
            {
                return n
            }
        }
        return null
    }

    fun parseSeqno(body: String): Long?
    {
        val doc = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val ok = doc["ok"]
        if (ok != null && !truthy(ok) && seqnoFrom(doc) == null)
        {
            return null
        }
        return seqnoFrom(doc)?.takeIf { it >= 0 }
    }

    private fun seqnoFrom(doc: JsonObject): Long?
    {
        val result = doc["result"]?.jsonObject
        if (result != null)
        {
            val last = result["last"]?.jsonObject
            val fromLast = last?.let { number(it["seqno"]) }
            if (fromLast != null && fromLast > 0)
            {
                return fromLast
            }
            val fromResult = number(result["seqno"])
            if (fromResult != null && fromResult > 0)
            {
                return fromResult
            }
        }
        return number(doc["seqno"])
    }

    private fun number(el: JsonElement?): Long?
    {
        val p = el as? JsonPrimitive ?: return null
        p.longOrNull?.let { return it }
        p.doubleOrNull?.let { return it.toLong() }
        return p.contentOrNull?.toLongOrNull()
    }

    private fun truthy(el: JsonElement): Boolean
    {
        val p = el as? JsonPrimitive ?: return true
        p.booleanOrNull?.let { return it }
        return when (p.contentOrNull?.trim()?.lowercase())
        {
            "0", "false", "no", "" -> false
            else -> true
        }
    }
}
