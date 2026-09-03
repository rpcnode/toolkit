package rpcnode.toolkit.panel.notifications.presentation.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.notifications.application.TelegramBotApi
import rpcnode.toolkit.notifications.application.TelegramBotApiResult
import rpcnode.toolkit.notifications.domain.model.TelegramBot
import rpcnode.toolkit.notifications.domain.model.TelegramBotToken
import rpcnode.toolkit.notifications.domain.model.TelegramChat
import rpcnode.toolkit.notifications.domain.model.TelegramChatMemberStatus
import rpcnode.toolkit.panel.presentation.http.ServerConfig
import rpcnode.toolkit.panel.presentation.http.module
import rpcnode.toolkit.panel.testToolkit

class NotificationsRoutesTest
{
    @Test
    fun wizard_configures_discovers_and_selects_a_chat_without_returning_token() = testApplication {
        val toolkit = testToolkit(telegramBotApi = wizardTelegram)
        application { module(ServerConfig(), toolkit) }
        val auth = setupAdmin()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/notifications/settings").status)

        val bot = client.post("/api/notifications/bot") {
            header(HttpHeaders.Authorization, "Bearer $auth")
            contentType(ContentType.Application.Json)
            setBody("""{"bot_token":"123456:abcdefghijklmnopqrstuvwxyzABCDE"}""")
        }
        assertEquals(HttpStatusCode.OK, bot.status)
        assertFalse(bot.bodyAsText().contains("abcdefghijklmnopqrstuvwxyzABCDE"))

        val chats = client.post("/api/notifications/chats") {
            header(HttpHeaders.Authorization, "Bearer $auth")
        }
        assertEquals(HttpStatusCode.OK, chats.status)
        val chatItems = Json.parseToJsonElement(chats.bodyAsText()).jsonObject["chats"]!!.toString()
        assertTrue(chatItems.contains("-10042"))

        val selected = client.post("/api/notifications/chat") {
            header(HttpHeaders.Authorization, "Bearer $auth")
            contentType(ContentType.Application.Json)
            setBody("""{"chat_id":-10042}""")
        }
        assertEquals(HttpStatusCode.OK, selected.status)
        val settings = Json.parseToJsonElement(selected.bodyAsText()).jsonObject
        assertEquals("-10042", settings["chat_id"]!!.jsonPrimitive.content)
        assertTrue(settings["verified"]!!.jsonPrimitive.boolean)
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.setupAdmin(): String
    {
        val response = client.post("/api/setup") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"secret-password"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }
}

private val wizardTelegram = object : TelegramBotApi
{
    override suspend fun getMe(token: TelegramBotToken) =
        TelegramBotApiResult.Ok(TelegramBot(99, "wizard_bot"))

    override suspend fun getUpdates(token: TelegramBotToken) =
        TelegramBotApiResult.Ok(listOf(TelegramChat(-10042, "supergroup", "Toolkit", "toolkit")))

    override suspend fun getChatMember(token: TelegramBotToken, chatId: Long, userId: Long) =
        TelegramBotApiResult.Ok(TelegramChatMemberStatus.ADMINISTRATOR)

    override suspend fun sendMessage(token: TelegramBotToken, chatId: Long, text: String) =
        TelegramBotApiResult.Ok(Unit)
}
