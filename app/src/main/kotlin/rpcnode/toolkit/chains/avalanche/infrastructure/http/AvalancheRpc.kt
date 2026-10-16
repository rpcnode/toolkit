package rpcnode.toolkit.chains.avalanche.infrastructure.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

private val json = Json { ignoreUnknownKeys = true }

/** Avalanche JSON-RPC helpers: C-Chain `eth_blockNumber` and the info API's bootstrap flag. */
object AvalancheRpc
{
    /** C-Chain RPC lives under `/ext/bc/C/rpc` on the HTTP port. */
    fun cChainUrl(base: String): String
    {
        val b = base.trim().trimEnd('/')
        return if (b.contains("/ext/bc/")) b else "$b/ext/bc/C/rpc"
    }

    fun blockNumberRequest(): String = """{"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}"""

    fun isBootstrappedRequest(chain: String = "C"): String =
        """{"jsonrpc":"2.0","id":1,"method":"info.isBootstrapped","params":{"chain":"$chain"}}"""

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
        val raw = hex.removePrefix("0x").removePrefix("0X")
        return if (raw.isEmpty()) 0L else raw.toLongOrNull(16)
    }

    /** `{"result":{"isBootstrapped":true}}`; null when the node does not answer. */
    fun parseBootstrapped(body: String?): Boolean?
    {
        if (body.isNullOrBlank())
        {
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val result = root["result"] as? kotlinx.serialization.json.JsonObject ?: return null
        return result["isBootstrapped"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    }

    suspend fun blockNumber(http: SimpleHttp, baseUrl: String): Long? =
        parseBlockNumber(http.postJson(cChainUrl(baseUrl), blockNumberRequest()))

    suspend fun isBootstrapped(http: SimpleHttp, baseUrl: String): Boolean?
    {
        val root = baseUrl.trim().trimEnd('/').substringBefore("/ext/")
        return parseBootstrapped(http.postJson("$root/ext/info", isBootstrappedRequest()))
    }
}
