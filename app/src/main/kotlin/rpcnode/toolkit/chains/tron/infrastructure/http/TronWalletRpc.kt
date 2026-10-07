package rpcnode.toolkit.chains.tron.infrastructure.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

/**
 * TRON FullNode HTTP API helpers (`/wallet/…`) — not eth-jsonrpc.
 * Base URL is the HTTP root (e.g. `http://127.0.0.1:18091` or QuickNode without `/jsonrpc`).
 */
object TronWalletRpc
{
    private val json = Json { ignoreUnknownKeys = true }

    /** Strip trailing slash and a mistaken `/jsonrpc` suffix (eth gateways). */
    fun normalizeBaseUrl(raw: String): String
    {
        var s = raw.trim().trimEnd('/')
        if (s.endsWith("/jsonrpc", ignoreCase = true))
        {
            s = s.dropLast("/jsonrpc".length).trimEnd('/')
        }
        return s
    }

    fun getnodeinfoUrl(base: String): String = "${normalizeBaseUrl(base)}/wallet/getnodeinfo"

    fun getnowblockUrl(base: String): String = "${normalizeBaseUrl(base)}/wallet/getnowblock"

    fun parseCodeVersion(body: String?): String?
    {
        if (body.isNullOrBlank() || looksLikeHtml(body))
        {
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val cfg = root["configNodeInfo"]?.jsonObject ?: return null
        return cfg["codeVersion"]?.jsonPrimitive?.contentOrNull?.trim()?.ifEmpty { null }
    }

    suspend fun codeVersion(http: SimpleHttp, baseUrl: String): String?
    {
        val url = getnodeinfoUrl(baseUrl)
        // java-tron accepts GET or POST {}; prefer GET like the Go sibling.
        val body = http.getText(url, accept = "application/json")
            ?: http.postJson(url, "{}")
        return parseCodeVersion(body)
    }

    suspend fun blockHeight(http: SimpleHttp, baseUrl: String): Long? =
        http.tronGetNowBlockHeight(getnowblockUrl(baseUrl))

    fun looksLikeHtml(body: String): Boolean
    {
        val t = body.trimStart()
        return t.startsWith("<") || t.startsWith("<!", ignoreCase = true)
    }
}
