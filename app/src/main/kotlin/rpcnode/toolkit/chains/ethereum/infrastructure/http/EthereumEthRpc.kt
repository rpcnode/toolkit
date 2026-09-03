package rpcnode.toolkit.chains.ethereum.infrastructure.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

private val json = Json { ignoreUnknownKeys = true }

/** Shared eth JSON-RPC helpers (local height + public tip + sync progress). */
object EthereumEthRpc
{
    fun blockNumberRequest(): String =
        """{"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}"""

    fun syncingRequest(): String =
        """{"jsonrpc":"2.0","id":1,"method":"eth_syncing","params":[]}"""

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

    /**
     * `eth_syncing` result: false → done; object → still syncing with optional block ratio.
     */
    data class SyncingStatus(
        val syncing: Boolean,
        val currentBlock: Long? = null,
        val highestBlock: Long? = null,
        /** 0..100 from current/highest when both known; 100 when not syncing. */
        val blockPct: Double? = null,
    )

    fun parseSyncing(body: String?): SyncingStatus?
    {
        if (body.isNullOrBlank())
        {
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root["error"] != null && root["error"] !is JsonPrimitive)
        {
            return null
        }
        val result = root["result"] ?: return null
        if (result is JsonPrimitive)
        {
            val asBool = result.booleanOrNull
            if (asBool != null)
            {
                return SyncingStatus(syncing = asBool, blockPct = if (asBool) null else 100.0)
            }
            // Unexpected primitive (e.g. string) — treat as not syncing unknown
            return null
        }
        val obj = result as? JsonObject ?: return SyncingStatus(syncing = false, blockPct = 100.0)
        val current = obj["currentBlock"]?.jsonPrimitive?.contentOrNull?.let { parseHexInt64(it) }
        val highest = obj["highestBlock"]?.jsonPrimitive?.contentOrNull?.let { parseHexInt64(it) }
        val pct = if (current != null && highest != null && highest > 0)
        {
            ((current.toDouble() / highest.toDouble()) * 100.0).coerceIn(0.0, 100.0)
        }
        else
        {
            null
        }
        return SyncingStatus(
            syncing = true,
            currentBlock = current,
            highestBlock = highest,
            blockPct = pct,
        )
    }

    /**
     * Geth snap logs: `Syncing: state download … synced=17.52%` /
     * `Syncing: chain download … synced=33.56%`. Bottleneck = min of latest state+chain.
     */
    fun parseSnapSyncPctFromLog(logText: String?): Double?
    {
        if (logText.isNullOrBlank())
        {
            return null
        }
        val stateRe = Regex("""state download in progress\s+synced=([0-9]+(?:\.[0-9]+)?)%""", RegexOption.IGNORE_CASE)
        val chainRe = Regex("""chain download in progress\s+synced=([0-9]+(?:\.[0-9]+)?)%""", RegexOption.IGNORE_CASE)
        fun lastPct(re: Regex): Double?
        {
            val m = re.findAll(logText).lastOrNull() ?: return null
            return m.groupValues[1].toDoubleOrNull()?.coerceIn(0.0, 100.0)
        }
        val state = lastPct(stateRe)
        val chain = lastPct(chainRe)
        return when
        {
            state != null && chain != null -> minOf(state, chain)
            state != null -> state
            chain != null -> chain
            else -> null
        }
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

    suspend fun syncing(http: SimpleHttp, url: String): SyncingStatus?
    {
        val endpoint = url.trim().trimEnd('/')
        if (endpoint.isEmpty())
        {
            return null
        }
        return parseSyncing(http.postJson(endpoint, syncingRequest()))
    }
}
