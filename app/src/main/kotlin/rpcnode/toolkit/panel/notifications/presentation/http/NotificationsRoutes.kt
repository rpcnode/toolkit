package rpcnode.toolkit.panel.notifications.presentation.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import rpcnode.toolkit.notifications.application.ConfigureTelegramBotResult
import rpcnode.toolkit.notifications.application.DiscoverTelegramChatsResult
import rpcnode.toolkit.notifications.application.SelectTelegramChatResult
import rpcnode.toolkit.notifications.application.SendTelegramTestResult
import rpcnode.toolkit.notifications.application.SetTelegramNotificationsEnabledResult
import rpcnode.toolkit.notifications.application.TelegramNotificationSettings
import rpcnode.toolkit.notifications.domain.model.StoredTelegramBotToken
import rpcnode.toolkit.notifications.domain.model.TelegramBot
import rpcnode.toolkit.notifications.domain.model.TelegramChat
import rpcnode.toolkit.panel.auth.presentation.http.sessionToken
import rpcnode.toolkit.wiring.Toolkit

@Serializable
data class TelegramBotRequest(
    @SerialName("bot_token") val botToken: String = "",
)

@Serializable
data class TelegramChatRequest(
    @SerialName("chat_id") val chatId: JsonElement? = null,
)

@Serializable
data class NotificationSettingsPutRequest(
    @SerialName("bot_token") val botToken: String? = null,
    @SerialName("chat_id") val chatId: String? = null,
    val enabled: Boolean? = null,
    @SerialName("clear_token") val clearToken: Boolean = false,
)

@Serializable
data class NotificationSettingsResponse(
    val ok: Boolean = true,
    val enabled: Boolean = false,
    @SerialName("chat_id") val chatId: String = "",
    @SerialName("has_token") val hasToken: Boolean = false,
    @SerialName("token_masked") val tokenMasked: String? = null,
    @SerialName("token_decrypt_ok") val tokenDecryptOk: Boolean = false,
    val verified: Boolean = false,
)

@Serializable
data class TelegramBotResponse(
    val ok: Boolean = true,
    val id: Long,
    val username: String? = null,
)

@Serializable
data class TelegramChatsResponse(
    val ok: Boolean = true,
    val chats: List<TelegramChatResponse>,
)

@Serializable
data class TelegramChatResponse(
    val id: Long,
    val type: String,
    val title: String,
    val username: String? = null,
)

@Serializable
data class TelegramTestResponse(
    val ok: Boolean = true,
    val sent: Boolean = true,
    val message: String = "Test message sent to Telegram.",
)

@Serializable
data class NotificationErrorResponse(
    val ok: Boolean = false,
    val error: String,
    val message: String,
)

fun Application.notificationsApiRoutes(toolkit: Toolkit)
{
    routing {
        get("/api/notifications/settings") {
            if (!call.notificationsAuthorized(toolkit))
            {
                return@get
            }
            call.respond(toolkit.getTelegramNotificationSettings().toResponse())
        }

        post("/api/notifications/bot") {
            if (!call.notificationsAuthorized(toolkit))
            {
                return@post
            }
            when (val result = toolkit.configureTelegramBot(call.receive<TelegramBotRequest>().botToken))
            {
                is ConfigureTelegramBotResult.Configured -> call.respond(result.bot.toResponse())
                ConfigureTelegramBotResult.InvalidFormat -> call.notificationError(
                    HttpStatusCode.BadRequest,
                    "bot_token_invalid_format",
                    "Enter the bot token from BotFather (digits, a colon, and its secret value).",
                )
                ConfigureTelegramBotResult.InvalidToken -> call.notificationError(
                    HttpStatusCode.BadRequest,
                    "bot_token_invalid",
                    "Telegram rejected this bot token. Copy it again from BotFather.",
                )
                is ConfigureTelegramBotResult.Rejected -> call.notificationError(
                    HttpStatusCode.BadRequest,
                    "bot_token_rejected",
                    result.message,
                )
                is ConfigureTelegramBotResult.Unavailable -> call.notificationError(
                    HttpStatusCode.BadGateway,
                    "telegram_unavailable",
                    result.message,
                )
            }
        }

        post("/api/notifications/chats") {
            if (!call.notificationsAuthorized(toolkit))
            {
                return@post
            }
            when (val result = toolkit.discoverTelegramChats())
            {
                is DiscoverTelegramChatsResult.Chats ->
                    call.respond(TelegramChatsResponse(chats = result.chats.map(TelegramChat::toResponse)))
                DiscoverTelegramChatsResult.TokenMissing -> call.notificationError(
                    HttpStatusCode.Conflict,
                    "bot_token_required",
                    "Save a valid bot token before looking for chats.",
                )
                DiscoverTelegramChatsResult.TokenCorrupt -> call.notificationError(
                    HttpStatusCode.Conflict,
                    "bot_token_unreadable",
                    "The stored bot token cannot be decrypted. Enter it again.",
                )
                DiscoverTelegramChatsResult.InvalidToken -> call.notificationError(
                    HttpStatusCode.BadRequest,
                    "bot_token_invalid",
                    "Telegram rejected the stored bot token. Enter it again.",
                )
                is DiscoverTelegramChatsResult.Rejected -> call.notificationError(
                    HttpStatusCode.BadRequest,
                    "telegram_updates_rejected",
                    result.message,
                )
                is DiscoverTelegramChatsResult.Unavailable -> call.notificationError(
                    HttpStatusCode.BadGateway,
                    "telegram_unavailable",
                    result.message,
                )
            }
        }

        post("/api/notifications/chat") {
            if (!call.notificationsAuthorized(toolkit))
            {
                return@post
            }
            val chatId = (call.receive<TelegramChatRequest>().chatId as? JsonPrimitive)
                ?.content
                ?.trim()
                ?.toLongOrNull()
            if (chatId == null)
            {
                call.notificationError(
                    HttpStatusCode.BadRequest,
                    "chat_id_invalid",
                    "Choose a numeric chat ID returned by chat discovery.",
                )
                return@post
            }
            call.respondSelection(toolkit, chatId)
        }

        // Existing page compatibility. New wizard clients should use /bot, /chats, and /chat.
        put("/api/notifications/settings") {
            if (!call.notificationsAuthorized(toolkit))
            {
                return@put
            }
            val request = call.receive<NotificationSettingsPutRequest>()
            if (request.clearToken)
            {
                toolkit.clearTelegramBot()
            }
            request.botToken?.takeIf { it.isNotBlank() }?.let { token ->
                when (val result = toolkit.configureTelegramBot(token))
                {
                    is ConfigureTelegramBotResult.Configured -> Unit
                    ConfigureTelegramBotResult.InvalidFormat -> return@put call.notificationError(
                        HttpStatusCode.BadRequest, "bot_token_invalid_format",
                        "Enter the bot token from BotFather (digits, a colon, and its secret value).",
                    )
                    ConfigureTelegramBotResult.InvalidToken -> return@put call.notificationError(
                        HttpStatusCode.BadRequest, "bot_token_invalid",
                        "Telegram rejected this bot token. Copy it again from BotFather.",
                    )
                    is ConfigureTelegramBotResult.Rejected -> return@put call.notificationError(
                        HttpStatusCode.BadRequest, "bot_token_rejected", result.message,
                    )
                    is ConfigureTelegramBotResult.Unavailable -> return@put call.notificationError(
                        HttpStatusCode.BadGateway, "telegram_unavailable", result.message,
                    )
                }
            }
            request.chatId?.takeIf { it.isNotBlank() }?.let { rawChatId ->
                val chatId = rawChatId.trim().toLongOrNull() ?: return@put call.notificationError(
                    HttpStatusCode.BadRequest, "chat_id_invalid",
                    "Choose a numeric chat ID returned by chat discovery.",
                )
                when (val result = toolkit.selectTelegramChat(chatId))
                {
                    is SelectTelegramChatResult.Selected -> Unit
                    else -> return@put call.respondSelectionResult(result)
                }
            }
            if (request.enabled != null)
            {
                when (toolkit.setTelegramNotificationsEnabled(request.enabled))
                {
                    SetTelegramNotificationsEnabledResult.Updated -> Unit
                    SetTelegramNotificationsEnabledResult.ChatMissing -> return@put call.notificationError(
                        HttpStatusCode.Conflict,
                        "chat_selection_required",
                        "Select a verified Telegram chat before enabling notifications.",
                    )
                }
            }
            call.respond(toolkit.getTelegramNotificationSettings().toResponse())
        }

        post("/api/notifications/test") {
            if (!call.notificationsAuthorized(toolkit))
            {
                return@post
            }
            when (val result = toolkit.sendTelegramTest())
            {
                SendTelegramTestResult.Sent -> call.respond(TelegramTestResponse())
                SendTelegramTestResult.TokenMissing -> call.notificationError(HttpStatusCode.Conflict, "bot_token_required", "Save a bot token first.")
                SendTelegramTestResult.TokenCorrupt -> call.notificationError(HttpStatusCode.Conflict, "bot_token_unreadable", "The stored bot token cannot be decrypted. Enter it again.")
                SendTelegramTestResult.ChatMissing -> call.notificationError(HttpStatusCode.Conflict, "chat_selection_required", "Select a verified Telegram chat first.")
                SendTelegramTestResult.InvalidToken -> call.notificationError(HttpStatusCode.BadRequest, "bot_token_invalid", "Telegram rejected the stored bot token. Enter it again.")
                is SendTelegramTestResult.Rejected -> call.notificationError(HttpStatusCode.BadRequest, "telegram_send_rejected", result.message)
                is SendTelegramTestResult.Unavailable -> call.notificationError(HttpStatusCode.BadGateway, "telegram_unavailable", result.message)
            }
        }

        post("/api/notifications/verify") {
            if (!call.notificationsAuthorized(toolkit))
            {
                return@post
            }
            val settings = toolkit.getTelegramNotificationSettings()
            val chatId = settings.chatId
            if (chatId == null)
            {
                call.notificationError(HttpStatusCode.Conflict, "chat_selection_required", "Select a Telegram chat first.")
                return@post
            }
            call.respondSelection(toolkit, chatId)
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondSelection(toolkit: Toolkit, chatId: Long)
{
    when (val result = toolkit.selectTelegramChat(chatId))
    {
        is SelectTelegramChatResult.Selected -> respond(toolkit.getTelegramNotificationSettings().toResponse())
        else -> respondSelectionResult(result)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondSelectionResult(result: SelectTelegramChatResult)
{
    when (result)
    {
        is SelectTelegramChatResult.Selected -> error("handled by caller")
        SelectTelegramChatResult.TokenMissing -> notificationError(HttpStatusCode.Conflict, "bot_token_required", "Save a valid bot token first.")
        SelectTelegramChatResult.TokenCorrupt -> notificationError(HttpStatusCode.Conflict, "bot_token_unreadable", "The stored bot token cannot be decrypted. Enter it again.")
        SelectTelegramChatResult.InvalidToken -> notificationError(HttpStatusCode.BadRequest, "bot_token_invalid", "Telegram rejected the stored bot token. Enter it again.")
        SelectTelegramChatResult.BotNotAdmin -> notificationError(HttpStatusCode.BadRequest, "bot_not_admin", "Make the bot an administrator of the selected group or channel, then try again.")
        is SelectTelegramChatResult.Rejected -> notificationError(HttpStatusCode.BadRequest, "telegram_chat_rejected", result.message)
        is SelectTelegramChatResult.Unavailable -> notificationError(HttpStatusCode.BadGateway, "telegram_unavailable", result.message)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.notificationsAuthorized(toolkit: Toolkit): Boolean
{
    if (toolkit.getAuthStatus(sessionToken()).authenticated)
    {
        return true
    }
    notificationError(HttpStatusCode.Unauthorized, "unauthorized", "Sign in at /login")
    return false
}

private suspend fun io.ktor.server.application.ApplicationCall.notificationError(
    status: HttpStatusCode,
    error: String,
    message: String,
)
{
    respond(status, NotificationErrorResponse(error = error, message = message))
}

private fun TelegramNotificationSettings.toResponse(): NotificationSettingsResponse
{
    val present = token as? StoredTelegramBotToken.Present
    return NotificationSettingsResponse(
        enabled = enabled,
        chatId = chatId?.toString().orEmpty(),
        hasToken = token !is StoredTelegramBotToken.Absent,
        tokenMasked = present?.token?.value?.masked(),
        tokenDecryptOk = present != null,
        verified = present != null && chatId != null,
    )
}

private fun TelegramBot.toResponse() = TelegramBotResponse(id = id, username = username)

private fun TelegramChat.toResponse() = TelegramChatResponse(
    id = id,
    type = type,
    title = title,
    username = username,
)

private fun String.masked(): String = "••••"
