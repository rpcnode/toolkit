package rpcnode.toolkit.notifications.infrastructure.http

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import rpcnode.toolkit.notifications.application.TelegramBotApi
import rpcnode.toolkit.notifications.application.TelegramBotApiResult
import rpcnode.toolkit.notifications.domain.model.TelegramBot
import rpcnode.toolkit.notifications.domain.model.TelegramBotToken
import rpcnode.toolkit.notifications.domain.model.TelegramChat
import rpcnode.toolkit.notifications.domain.model.TelegramChatMemberStatus
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Telegram's token is necessarily part of its Bot API path. This adapter deliberately does not
 * use the shared HTTP logging helpers, so that path is never logged by the panel.
 */
class HttpTelegramBotApi(
    private val http: HttpClient = SimpleHttpClients.cio(),
) : TelegramBotApi
{
    override suspend fun getMe(token: TelegramBotToken): TelegramBotApiResult<TelegramBot> =
        request(token, "getMe") { http.get(it) }.map { result ->
            val objectResult = result.asObject() ?: return@map null
            val id = objectResult["id"].asLong() ?: return@map null
            TelegramBot(id = id, username = objectResult["username"].asString())
        }

    override suspend fun getUpdates(token: TelegramBotToken): TelegramBotApiResult<List<TelegramChat>> =
        request(token, "getUpdates") { url ->
            http.get(url) {
                parameter("limit", 100)
                parameter("allowed_updates", """["message","channel_post","edited_message","edited_channel_post"]""")
            }
        }.map { result ->
            val updates = result as? JsonArray ?: return@map null
            updates.mapNotNull(::chatFromUpdate).distinctBy { it.id }
        }

    override suspend fun getChatMember(
        token: TelegramBotToken,
        chatId: Long,
        userId: Long,
    ): TelegramBotApiResult<TelegramChatMemberStatus> =
        request(token, "getChatMember") { url ->
            http.get(url) {
                parameter("chat_id", chatId)
                parameter("user_id", userId)
            }
        }.map { result ->
            when (result.asObject()?.get("status").asString()?.lowercase())
            {
                "administrator" -> TelegramChatMemberStatus.ADMINISTRATOR
                "creator" -> TelegramChatMemberStatus.CREATOR
                "owner" -> TelegramChatMemberStatus.OWNER
                "member" -> TelegramChatMemberStatus.MEMBER
                "restricted" -> TelegramChatMemberStatus.RESTRICTED
                "left" -> TelegramChatMemberStatus.LEFT
                "kicked" -> TelegramChatMemberStatus.KICKED
                else -> TelegramChatMemberStatus.UNKNOWN
            }
        }

    override suspend fun sendMessage(
        token: TelegramBotToken,
        chatId: Long,
        text: String,
    ): TelegramBotApiResult<Unit> =
        request(token, "sendMessage") { url ->
            http.post(url) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("chat_id", chatId)
                    put("text", text)
                }.toString())
            }
        }.map { Unit }

    private suspend fun request(
        token: TelegramBotToken,
        method: String,
        call: suspend (String) -> HttpResponse,
    ): TelegramBotApiResult<JsonElement>
    {
        val response = try
        {
            call("$API_BASE/bot${token.value}/$method")
        }
        catch (_: Exception)
        {
            return TelegramBotApiResult.Unavailable("Telegram could not be reached. Try again.")
        }
        val body = try
        {
            response.bodyAsText()
        }
        catch (_: Exception)
        {
            return TelegramBotApiResult.Unavailable("Telegram returned an unreadable response. Try again.")
        }
        val envelope = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return TelegramBotApiResult.Unavailable("Telegram returned an invalid response. Try again.")
        if (envelope["ok"]?.jsonPrimitive?.booleanOrNull == true)
        {
            return TelegramBotApiResult.Ok(envelope["result"] ?: return TelegramBotApiResult.Unavailable("Telegram returned no result."))
        }
        val code = envelope["error_code"].asLong()?.toInt()
        if (response.status.value == 401 || code == 401)
        {
            return TelegramBotApiResult.InvalidToken
        }
        val detail = envelope["description"].asString()?.take(MAX_ERROR_LENGTH)
            ?: "Telegram rejected the request."
        return TelegramBotApiResult.Rejected(detail)
    }

    private fun <T> TelegramBotApiResult<JsonElement>.map(
        transform: (JsonElement) -> T?,
    ): TelegramBotApiResult<T> = when (this)
    {
        is TelegramBotApiResult.Ok -> transform(value)?.let { TelegramBotApiResult.Ok(it) }
            ?: TelegramBotApiResult.Unavailable("Telegram returned an incomplete response. Try again.")
        TelegramBotApiResult.InvalidToken -> TelegramBotApiResult.InvalidToken
        is TelegramBotApiResult.Rejected -> TelegramBotApiResult.Rejected(message)
        is TelegramBotApiResult.Unavailable -> TelegramBotApiResult.Unavailable(message)
    }

    private fun chatFromUpdate(update: JsonElement): TelegramChat?
    {
        val updateObject = update.asObject() ?: return null
        val message = UPDATE_MESSAGE_FIELDS.asSequence()
            .mapNotNull { updateObject[it].asObject() }
            .firstOrNull()
            ?: return null
        val chat = message["chat"].asObject() ?: return null
        val type = chat["type"].asString() ?: return null
        if (type !in DISCOVERABLE_CHAT_TYPES)
        {
            return null
        }
        val id = chat["id"].asLong() ?: return null
        val username = chat["username"].asString()
        return TelegramChat(
            id = id,
            type = type,
            title = chat["title"].asString() ?: username ?: id.toString(),
            username = username,
        )
    }

    private companion object
    {
        const val API_BASE = "https://api.telegram.org"
        const val MAX_ERROR_LENGTH = 240
        val UPDATE_MESSAGE_FIELDS = listOf("message", "channel_post", "edited_message", "edited_channel_post")
        val DISCOVERABLE_CHAT_TYPES = setOf("group", "supergroup", "channel")
        val json = Json { ignoreUnknownKeys = true }

        fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
        fun JsonElement?.asString(): String? = (this as? JsonPrimitive)
            ?.takeUnless { it is JsonNull }
            ?.content
        fun JsonElement?.asLong(): Long? = (this as? JsonPrimitive)?.longOrNull
    }
}
