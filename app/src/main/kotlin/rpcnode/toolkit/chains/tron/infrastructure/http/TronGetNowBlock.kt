package rpcnode.toolkit.chains.tron.infrastructure.http

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

/**
 * Same FullNode call for local height and public tip: `POST …/wallet/getnowblock`.
 * Local: `http://127.0.0.1:{httpPort}/wallet/getnowblock`.
 * Public: YAML `publicTip.urls` (e.g. Trongrid) — same path and body.
 */
suspend fun SimpleHttp.tronGetNowBlockHeight(url: String): Long?
{
    val body = postJson(url.trim()) ?: return null
    return parseTronBlockHeight(body)
}

fun parseTronBlockHeight(body: String, json: Json = Json { ignoreUnknownKeys = true }): Long?
{
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val header = root["block_header"]?.jsonObject ?: return null
    val raw = header["raw_data"]?.jsonObject ?: return null
    return raw["number"]?.jsonPrimitive?.longOrNull
}
