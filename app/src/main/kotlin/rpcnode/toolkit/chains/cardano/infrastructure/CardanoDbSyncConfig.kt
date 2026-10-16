package rpcnode.toolkit.chains.cardano.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `<node_dir>/config/db-sync-config.json`. db-sync reads the genesis files, protocol and hard-fork settings from the node's
 * own `config.json` (`NodeConfigFile`, resolved next to this file), so it always follows the node; the file itself only holds
 * the db-sync settings, the Prometheus port and the logging block of IOG's db-sync-config.json (identical for the three envs).
 * Written on every Start. Constants = `clientConfig.bindings` literals in network.yml.
 */
object CardanoDbSyncConfig
{
    const val TX_CBOR = "disable"
    const val TX_OUT_VALUE = "enable"
    const val TX_OUT_USE_ADDRESS_TABLE = false
    const val LEDGER = "enable"
    const val SHELLEY = true
    const val MULTI_ASSET = true
    const val METADATA = true
    const val PLUTUS = true
    const val GOVERNANCE = "enable"
    const val OFFCHAIN_POOL_DATA = "disable"
    const val OFFCHAIN_VOTE_DATA = "disable"
    const val POOL_STAT = "enable"
    const val JSON_TYPE = "text"
    const val DISABLE_EPOCH = false

    private val json = Json { prettyPrint = true }

    /** [base] is `chains/cardano/db-sync-config.base.json` (IOG's mainnet file); the env, the Prometheus port and the options are set here. */
    fun configJson(base: String, env: String, prometheusPort: Int): String
    {
        val root = runCatching { json.parseToJsonElement(base) }.getOrNull() as? JsonObject
            ?: throw IllegalArgumentException("db-sync base config is not a JSON object")
        val e = CardanoClusters.normalizeEnv(env)
        val out = linkedMapOf<String, JsonElement>()
        out.putAll(root)
        out["NetworkName"] = JsonPrimitive(e)
        out["RequiresNetworkMagic"] = JsonPrimitive(if (e == "mainnet") "RequiresNoMagic" else "RequiresMagic")
        out["NodeConfigFile"] = JsonPrimitive(CardanoUnitBodies.CONFIG_JSON)
        out["PrometheusPort"] = JsonPrimitive(prometheusPort)
        out["insert_options"] = insertOptions()
        out["ledger_backend"] = JsonPrimitive(CardanoIndexerBodies.DBSYNC_LEDGER_BACKEND)
        return json.encodeToString(JsonObject.serializer(), JsonObject(out)) + "\n"
    }

    fun insertOptions(): JsonObject = buildJsonObject {
        put("tx_cbor", TX_CBOR)
        put(
            "tx_out",
            buildJsonObject {
                put("value", TX_OUT_VALUE)
                put("use_address_table", TX_OUT_USE_ADDRESS_TABLE)
            },
        )
        put("ledger", LEDGER)
        put("shelley", buildJsonObject { put("enable", SHELLEY) })
        put("multi_asset", buildJsonObject { put("enable", MULTI_ASSET) })
        put("metadata", buildJsonObject { put("enable", METADATA) })
        put("plutus", buildJsonObject { put("enable", PLUTUS) })
        put("governance", GOVERNANCE)
        put("offchain_pool_data", OFFCHAIN_POOL_DATA)
        put("offchain_vote_data", OFFCHAIN_VOTE_DATA)
        put("pool_stat", POOL_STAT)
        put("json_type", JSON_TYPE)
        put("disable_epoch", DISABLE_EPOCH)
    }
}
