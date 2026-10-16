package rpcnode.toolkit.chains.near.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Boot-node discovery and `config.json` patching (the official epoch-sync guide, near-nodes.io/intro/node-epoch-sync). */
object NearConfigPatch
{
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val lenient = Json { ignoreUnknownKeys = true }

    /**
     * `peer_id@addr` of every active peer that is also a known block producer — what the guide's `jq` pipeline prints.
     * The `network_info` result has `active_peers[{id, addr, account_id}]` and `known_producers[{peer_id, account_id, addr}]`.
     */
    fun bootNodes(networkInfoBody: String?): List<String>
    {
        if (networkInfoBody.isNullOrBlank()) return emptyList()
        val result = runCatching { lenient.parseToJsonElement(networkInfoBody).jsonObject["result"]?.jsonObject }.getOrNull()
            ?: return emptyList()
        val producers = (result["known_producers"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("peer_id")?.jsonPrimitive?.contentOrNull }
            .toSet()
        val out = LinkedHashSet<String>()
        for (peer in (result["active_peers"] as? kotlinx.serialization.json.JsonArray).orEmpty())
        {
            val o = peer as? JsonObject ?: continue
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val addr = o["addr"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" } ?: continue
            if (id in producers) out += "$id@$addr"
        }
        return out.toList()
    }

    /** Current `network.boot_nodes` of a config.json text (comma separated string), or empty. */
    fun currentBootNodes(configText: String): String =
        runCatching {
            lenient.parseToJsonElement(configText).jsonObject["network"]?.jsonObject?.get("boot_nodes")?.jsonPrimitive?.contentOrNull.orEmpty()
        }.getOrDefault("")

    /**
     * [configText] with Start's keys set: `network.boot_nodes` (only when [bootNodes] is not empty — an empty list keeps
     * the current one), `rpc.enable_debug_rpc`, `view_client_threads`. Everything else, in its order, is untouched.
     */
    fun apply(configText: String, bootNodes: List<String>): String
    {
        val root = lenient.parseToJsonElement(configText).jsonObject
        val network = if (bootNodes.isEmpty()) root["network"] else set(root["network"], "boot_nodes", JsonPrimitive(bootNodes.joinToString(",")))
        var next = root
        if (network != null) next = JsonObject(next + ("network" to network))
        next = JsonObject(next + ("rpc" to set(next["rpc"], "enable_debug_rpc", JsonPrimitive(NearSettings.ENABLE_DEBUG_RPC))))
        next = JsonObject(next + ("view_client_threads" to JsonPrimitive(NearSettings.VIEW_CLIENT_THREADS)))
        return pretty.encodeToString(JsonObject.serializer(), next) + "\n"
    }

    private fun set(section: JsonElement?, key: String, value: JsonElement): JsonObject
    {
        val obj = (section as? JsonObject) ?: JsonObject(emptyMap())
        return JsonObject(obj + (key to value))
    }

    private fun kotlinx.serialization.json.JsonArray?.orEmpty() = this ?: kotlinx.serialization.json.JsonArray(emptyList())
}
