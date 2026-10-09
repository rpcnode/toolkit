package rpcnode.toolkit.chains.bitcore.infrastructure

import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Public tip from Blockchair stats API (`data.blocks`).
 * Used for UTXO forks without a mempool.space-style plain-text height endpoint.
 */
class BlockchairStatsTipProbe(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(8))),
) : NetworkTipProbe
{
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun tip(urls: List<String>): Long?
    {
        for (url in urls)
        {
            val u = url.trim()
            if (u.isEmpty()) continue
            val body = http.getText(u, accept = "application/json") ?: continue
            val height = parseBlocks(body) ?: continue
            return height
        }
        return null
    }

    private fun parseBlocks(body: String): Long?
    {
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["data"]?.jsonObject?.get("blocks")?.jsonPrimitive?.longOrNull
        }.getOrNull()
    }
}
