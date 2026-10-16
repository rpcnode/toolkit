package rpcnode.toolkit.chains.cardano.infrastructure.http

import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** Public tip = `block_no` of the newest row of Koios `GET /api/v1/tip` (YAML `publicTip.urls`). */
class CardanoNetworkTipProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    override suspend fun tip(urls: List<String>): Long?
    {
        for (url in urls)
        {
            if (url.isBlank()) continue
            parse(http.getText(url.trim()))?.let { return it }
        }
        return null
    }

    companion object
    {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String?): Long?
        {
            if (body.isNullOrBlank()) return null
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
            val row = (root as? JsonArray)?.firstOrNull() as? JsonObject ?: root as? JsonObject ?: return null
            return row["block_no"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        }
    }
}
