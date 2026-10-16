package rpcnode.toolkit.chains.polygon.infrastructure.http

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

private val json = Json { ignoreUnknownKeys = true }

/** `result.sync_info` of Heimdall's CometBFT `/status`. */
data class HeimdallSync(
    val catchingUp: Boolean,
    val latestBlockHeight: Long,
    /** Unix seconds of the latest block Heimdall has applied. */
    val latestBlockTime: Long,
)

object PolygonHeimdallStatus
{
    fun parse(body: String?): HeimdallSync?
    {
        if (body.isNullOrBlank())
        {
            return null
        }
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val info = (root["result"] as? JsonObject)?.get("sync_info") as? JsonObject ?: return null
        val catchingUp = info["catching_up"]?.jsonPrimitive?.booleanOrNull ?: return null
        val height = info["latest_block_height"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return null
        val time = info["latest_block_time"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
            ?: return null
        return HeimdallSync(catchingUp, height, time)
    }

    suspend fun fetch(http: SimpleHttp, rpcUrl: String): HeimdallSync? =
        parse(http.getText(rpcUrl.trim().trimEnd('/') + "/status"))
}
