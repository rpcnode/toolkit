package rpcnode.toolkit.chains.cometbft.infrastructure.http

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

/** CometBFT `GET /status` → `result.sync_info` (identical on CometBFT 0.38, celestia-core and Sei's tendermint fork). */
object CometbftStatus
{
    data class Reading(
        val height: Long,
        val catchingUp: Boolean,
        val latestBlockTime: Instant?,
        val network: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String?): Reading?
    {
        if (body.isNullOrBlank()) return null
        val result = (runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null)["result"] as? JsonObject
            ?: return null
        val sync = result["sync_info"] as? JsonObject ?: return null
        val height = sync["latest_block_height"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        val catching = sync["catching_up"]?.jsonPrimitive?.let { runCatching { it.boolean }.getOrNull() } ?: false
        val time = sync["latest_block_time"]?.jsonPrimitive?.contentOrNull?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val network = (result["node_info"] as? JsonObject)?.get("network")?.jsonPrimitive?.contentOrNull.orEmpty()
        return Reading(height, catching, time, network)
    }

    suspend fun fetch(http: SimpleHttp, baseUrl: String): Reading? =
        parse(http.getText(baseUrl.trim().trimEnd('/') + "/status"))
}
