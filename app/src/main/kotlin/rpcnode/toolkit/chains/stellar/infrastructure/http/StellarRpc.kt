package rpcnode.toolkit.chains.stellar.infrastructure.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

private val json = Json { ignoreUnknownKeys = true }

/** `getHealth` outcome. stellar-rpc answers an error (not `unhealthy`) while it is behind or still initialising. */
data class StellarHealth(
    val ok: Boolean,
    val latestLedger: Long = 0,
    val oldestLedger: Long = 0,
    val error: String = "",
    val initializing: Boolean = false,
    val behind: Boolean = false,
)

/** stellar-rpc JSON-RPC helpers shared by the local height probe and the public tip probe. */
object StellarRpc
{
    /** No `params` key: stellar-rpc rejects `"params": null` for getHealth. */
    fun healthBody(): String = """{"jsonrpc":"2.0","id":1,"method":"getHealth"}"""

    fun latestLedgerBody(): String = """{"jsonrpc":"2.0","id":1,"method":"getLatestLedger"}"""

    fun parseHealth(raw: String): StellarHealth?
    {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val result = root["result"] as? JsonObject
        if (result != null && result["status"]?.jsonPrimitive?.contentOrNull == "healthy")
        {
            return StellarHealth(
                ok = true,
                latestLedger = result["latestLedger"]?.jsonPrimitive?.longOrNull ?: 0,
                oldestLedger = result["oldestLedger"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
        val error = root["error"] as? JsonObject ?: return null
        val message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return StellarHealth(
            ok = false,
            error = message,
            initializing = message.contains("not initialized", ignoreCase = true),
            behind = message.contains("latency", ignoreCase = true),
        )
    }

    fun parseLatestSequence(raw: String): Long?
    {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val result = root["result"] as? JsonObject ?: return null
        return result["sequence"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
    }

    suspend fun health(http: SimpleHttp, baseUrl: String): StellarHealth?
    {
        val body = http.postJson(baseUrl.trim().trimEnd('/'), healthBody()) ?: return null
        return parseHealth(body)
    }

    suspend fun latestSequence(http: SimpleHttp, baseUrl: String): Long?
    {
        val body = http.postJson(baseUrl.trim().trimEnd('/'), latestLedgerBody()) ?: return null
        return parseLatestSequence(body)
    }
}
