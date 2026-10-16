package rpcnode.toolkit.chains.cardano.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/**
 * Install option group `indexer`: which companions run beside cardano-node.
 * `none` (default — an existing node keeps what it had), `ogmios` (JSON-RPC / WebSocket on loopback), `dbsync` (cardano-db-sync
 * into PostgreSQL), `both`.
 */
enum class CardanoIndexerMode(val id: String, val ogmios: Boolean, val dbSync: Boolean)
{
    NONE("none", ogmios = false, dbSync = false),
    OGMIOS("ogmios", ogmios = true, dbSync = false),
    DBSYNC("dbsync", ogmios = false, dbSync = true),
    BOTH("both", ogmios = true, dbSync = true),
    ;

    companion object
    {
        const val GROUP = "indexer"
        val DEFAULT = OGMIOS

        fun parse(raw: String?): CardanoIndexerMode =
            when (raw?.trim()?.lowercase())
            {
                NONE.id -> NONE
                OGMIOS.id -> OGMIOS
                DBSYNC.id -> DBSYNC
                BOTH.id -> BOTH
                else -> DEFAULT
            }

        fun fromJson(installOptionsJson: String?): CardanoIndexerMode
        {
            val raw = installOptionsJson?.trim().orEmpty()
            if (raw.isEmpty())
            {
                return DEFAULT
            }
            val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return DEFAULT
            return parse(root[GROUP]?.jsonPrimitive?.contentOrNull)
        }
    }
}
