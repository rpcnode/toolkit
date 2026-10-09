package rpcnode.toolkit.chains.xrpl.infrastructure.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

/** Parsed `server_info` result for local height / public tip. */
data class XrplServerInfo(
    val ok: Boolean,
    val state: String = "",
    val complete: String = "",
    val seq: Long = 0,
    val peers: Int = 0,
    val error: String = "",
)

/**
 * XRPL JSON-RPC helpers (`server_info`).
 */
object XrplRpc
{
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun serverInfo(http: SimpleHttp, baseUrl: String): XrplServerInfo?
    {
        val url = normalizeRpcUrl(baseUrl) ?: return null
        val body = http.postJson(
            url,
            """{"method":"server_info","params":[{}]}""",
        ) ?: return null
        return parseServerInfo(body)
    }

    fun parseServerInfo(raw: String): XrplServerInfo?
    {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val result = root["result"]?.jsonObject ?: return XrplServerInfo(ok = false, error = "missing result")
        val status = result["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (status.isNotEmpty() && status != "success")
        {
            val msg = result["error_message"]?.jsonPrimitive?.contentOrNull
                ?: result["error"]?.jsonPrimitive?.contentOrNull
                ?: status
            return XrplServerInfo(ok = false, error = msg)
        }
        val info = result["info"]?.jsonObject ?: return XrplServerInfo(ok = false, error = "missing info")
        val state = info["server_state"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val complete = info["complete_ledgers"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val peers = info["peers"]?.jsonPrimitive?.doubleOrNull?.toInt()
            ?: info["peers"]?.jsonPrimitive?.longOrNull?.toInt()
            ?: 0
        val seq = (info["validated_ledger"] as? JsonObject)
            ?.get("seq")
            ?.jsonPrimitive
            ?.longOrNull
            ?: (info["validated_ledger"] as? JsonObject)
                ?.get("seq")
                ?.jsonPrimitive
                ?.doubleOrNull
                ?.toLong()
            ?: 0L
        return XrplServerInfo(
            ok = true,
            state = state,
            complete = complete,
            seq = seq,
            peers = peers,
        )
    }

    private fun normalizeRpcUrl(raw: String): String?
    {
        val u = raw.trim().trimEnd('/')
        if (u.isEmpty())
        {
            return null
        }
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "http://$u"
    }
}
