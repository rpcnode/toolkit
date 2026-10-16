package rpcnode.toolkit.chains.nonevm.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

/**
 * How one chain's block height is read over HTTP. The same reader serves the local node (`http://127.0.0.1:<port>` +
 * [localPath]) and the public tip (the URL exactly as listed in `publicTip.urls`), so a lag is always like-for-like.
 * Every read answers null instead of throwing: a probe that cannot read is skipped, never fatal.
 */
sealed interface HeightReader
{
    /** Appended to `http://127.0.0.1:<port>` for the local node. */
    val localPath: String

    suspend fun read(http: SimpleHttp, url: String): Long?

    fun localUrl(port: Int): String = "http://127.0.0.1:$port$localPath"

    /** `POST` JSON-RPC; the height is the number (or `0x…` hex string) at [result] of the response. */
    data class JsonRpc(
        val method: String,
        val params: String = "[]",
        val result: List<String>,
        override val localPath: String = "/",
    ) : HeightReader
    {
        override suspend fun read(http: SimpleHttp, url: String): Long? =
            parse(http.postJson(url, """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""))

        fun parse(body: String?): Long? = HeightJson.long(HeightJson.root(body), listOf("result") + result)
    }

    /** `GET` a JSON document; the height is the number / numeric string at [field]. */
    data class GetJson(override val localPath: String, val field: List<String>) : HeightReader
    {
        override suspend fun read(http: SimpleHttp, url: String): Long? = parse(http.getText(url))

        fun parse(body: String?): Long? = HeightJson.long(HeightJson.root(body), field)
    }

    /** `GET` whose body is the bare number (Aleo `block/height/latest`). */
    data class GetPlain(override val localPath: String) : HeightReader
    {
        override suspend fun read(http: SimpleHttp, url: String): Long? = parse(http.getText(url))

        fun parse(body: String?): Long? = body?.trim()?.trim('"')?.toLongOrNull()
    }

    /** `POST` a GraphQL query; the height is the number / numeric string at [field] inside `data`. */
    data class GraphQl(override val localPath: String, val query: String, val field: List<String>) : HeightReader
    {
        override suspend fun read(http: SimpleHttp, url: String): Long?
        {
            val body = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("query" to JsonPrimitive(query))))
            return parse(http.postJson(url, body))
        }

        fun parse(body: String?): Long? = HeightJson.long(HeightJson.root(body), listOf("data") + field)
    }
}

/** JSON walking shared by the readers (and by the probes that need a second field of the same document). */
object HeightJson
{
    private val json = Json { ignoreUnknownKeys = true }

    fun root(body: String?): JsonElement? =
        if (body.isNullOrBlank()) null else runCatching { json.parseToJsonElement(body) }.getOrNull()

    fun node(root: JsonElement?, path: List<String>): JsonElement?
    {
        var cur: JsonElement? = root
        for (key in path)
        {
            cur = (cur as? JsonObject)?.get(key) ?: return null
        }
        return cur
    }

    /** A JSON number, a numeric string or a `0x…` hex string. */
    fun long(root: JsonElement?, path: List<String>): Long?
    {
        val text = (node(root, path) as? JsonPrimitive)?.contentOrNull?.trim() ?: return null
        return if (text.startsWith("0x") || text.startsWith("0X")) text.drop(2).toLongOrNull(16) else text.toLongOrNull()
    }

    fun bool(root: JsonElement?, path: List<String>): Boolean? =
        (node(root, path) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    fun obj(root: JsonElement?, path: List<String>): JsonObject? = node(root, path)?.let { runCatching { it.jsonObject }.getOrNull() }
}
