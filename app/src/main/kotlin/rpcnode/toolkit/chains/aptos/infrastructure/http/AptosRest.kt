package rpcnode.toolkit.chains.aptos.infrastructure.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

private val json = Json { ignoreUnknownKeys = true }

data class LedgerInfo(val version: Long, val timestampMicros: Long?)

/** `GET {base}/v1` of the Aptos REST API: height (`ledger_version`) for the local probe and the public tip. */
object AptosRest
{
    fun ledgerUrl(base: String): String
    {
        val trimmed = base.trim().trimEnd('/')
        return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
    }

    fun parseLedger(body: String?): LedgerInfo?
    {
        if (body.isNullOrBlank())
        {
            return null
        }
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val version = root["ledger_version"]?.jsonPrimitive?.contentOrNull?.trim()?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val stamp = root["ledger_timestamp"]?.jsonPrimitive?.contentOrNull?.trim()?.toLongOrNull()
        return LedgerInfo(version, stamp)
    }

    suspend fun ledger(http: SimpleHttp, baseUrl: String): LedgerInfo? =
        parseLedger(http.getText(ledgerUrl(baseUrl), accept = "application/json"))

    suspend fun ledgerVersion(http: SimpleHttp, baseUrl: String): Long? = ledger(http, baseUrl)?.version
}
