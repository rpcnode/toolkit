package rpcnode.toolkit.chains.starknet.infrastructure.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

/**
 * Starknet JSON-RPC helpers (local Juno height + public tip). Unlike Ethereum, `starknet_blockNumber` answers a plain JSON
 * integer: `{"jsonrpc":"2.0","result":16335870,"id":1}` (VERIFIED on starknet-rpc.publicnode.com and api.zan.top).
 */
object StarknetRpc
{
    private val json = Json { ignoreUnknownKeys = true }

    fun blockNumberRequest(): String = """{"jsonrpc":"2.0","id":1,"method":"starknet_blockNumber","params":[]}"""

    fun syncingRequest(): String = """{"jsonrpc":"2.0","id":1,"method":"starknet_syncing","params":[]}"""

    /** A height as the node writes it: JSON integer (spec >= 0.8), tolerated as a quoted decimal or `0x` hex string too. */
    private fun height(p: JsonPrimitive): Long?
    {
        p.longOrNull?.let { return it.takeIf { v -> v >= 0 } }
        val text = p.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val value = if (text.startsWith("0x", ignoreCase = true)) text.substring(2).toLongOrNull(16) else text.toLongOrNull()
        return value?.takeIf { it >= 0 }
    }

    private fun result(body: String?): kotlinx.serialization.json.JsonElement?
    {
        if (body.isNullOrBlank())
        {
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        // `{"error":{"code":32,"message":"There are no blocks"}}` while the DB is still empty
        if (root["error"] != null)
        {
            return null
        }
        return root["result"]
    }

    fun parseBlockNumber(body: String?): Long? = (result(body) as? JsonPrimitive)?.let(::height)

    data class Syncing(
        val syncing: Boolean,
        val currentBlock: Long? = null,
        val highestBlock: Long? = null,
        /** 0..100 from current/highest while syncing; 100 when the node reports `false`. */
        val pct: Double? = null,
    )

    /** `starknet_syncing`: `false`, or `{starting_block_num, current_block_num, highest_block_num, ...}`. */
    fun parseSyncing(body: String?): Syncing?
    {
        val result = result(body) ?: return null
        if (result is JsonPrimitive)
        {
            val flag = result.booleanOrNull ?: return null
            return Syncing(syncing = flag, pct = if (flag) null else 100.0)
        }
        val obj = result as? JsonObject ?: return null
        val current = (obj["current_block_num"] as? JsonPrimitive)?.let(::height)
        val highest = (obj["highest_block_num"] as? JsonPrimitive)?.let(::height)
        val pct = if (current != null && highest != null && highest > 0)
        {
            (current.toDouble() * 100.0 / highest.toDouble()).coerceIn(0.0, 100.0)
        }
        else
        {
            null
        }
        return Syncing(syncing = true, currentBlock = current, highestBlock = highest, pct = pct)
    }

    suspend fun blockNumber(http: SimpleHttp, url: String): Long?
    {
        val endpoint = url.trim().trimEnd('/')
        return if (endpoint.isEmpty()) null else parseBlockNumber(http.postJson(endpoint, blockNumberRequest()))
    }

    suspend fun syncing(http: SimpleHttp, url: String): Syncing?
    {
        val endpoint = url.trim().trimEnd('/')
        return if (endpoint.isEmpty()) null else parseSyncing(http.postJson(endpoint, syncingRequest()))
    }
}
