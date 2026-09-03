package rpcnode.toolkit.notifications.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.notifications.domain.model.StoredTelegramBotToken
import rpcnode.toolkit.notifications.domain.model.TelegramBot
import rpcnode.toolkit.notifications.domain.model.TelegramBotToken
import rpcnode.toolkit.notifications.domain.model.TelegramChat
import rpcnode.toolkit.notifications.domain.model.TelegramChatMemberStatus
import rpcnode.toolkit.notifications.domain.repository.NotificationSettingsStore

class TelegramNotificationUseCasesTest
{
    @Test
    fun configuring_validates_before_persisting_and_resets_previous_chat() = runTest {
        val store = FakeNotificationSettingsStore(
            token = StoredTelegramBotToken.Present(token()),
            chatId = -1001,
            enabled = true,
        )
        val telegram = FakeTelegramBotApi(getMe = TelegramBotApiResult.InvalidToken)

        val rejected = ConfigureTelegramBotUseCase(store, telegram)("123456:abcdefghijklmnopqrstuvwxyzABCDE")

        assertEquals(ConfigureTelegramBotResult.InvalidToken, rejected)
        assertEquals(-1001, store.chatId)
        assertEquals(true, store.enabled)

        telegram.getMe = TelegramBotApiResult.Ok(TelegramBot(7, "toolkit_bot"))
        val configured = ConfigureTelegramBotUseCase(store, telegram)("123456:abcdefghijklmnopqrstuvwxyzABCDE")

        assertIs<ConfigureTelegramBotResult.Configured>(configured)
        assertEquals(null, store.chatId)
        assertEquals(false, store.enabled)
        assertEquals("123456:abcdefghijklmnopqrstuvwxyzABCDE", (store.token as StoredTelegramBotToken.Present).token.value)
    }

    @Test
    fun discovery_deduplicates_chats() = runTest {
        val store = FakeNotificationSettingsStore(token = StoredTelegramBotToken.Present(token()))
        val telegram = FakeTelegramBotApi(
            updates = TelegramBotApiResult.Ok(
                listOf(
                    TelegramChat(-1002, "channel", "Zeta", null),
                    TelegramChat(-1001, "supergroup", "Alpha", "alpha"),
                    TelegramChat(-1002, "channel", "Zeta", null),
                ),
            ),
        )

        val result = DiscoverTelegramChatsUseCase(store, telegram)()

        assertEquals(
            listOf(-1001L, -1002L),
            assertIs<DiscoverTelegramChatsResult.Chats>(result).chats.map { it.id },
        )
    }

    @Test
    fun selecting_chat_requires_bot_to_be_an_administrator() = runTest {
        val store = FakeNotificationSettingsStore(token = StoredTelegramBotToken.Present(token()))
        val telegram = FakeTelegramBotApi(member = TelegramBotApiResult.Ok(TelegramChatMemberStatus.MEMBER))
        val useCase = SelectTelegramChatUseCase(store, telegram)

        assertEquals(SelectTelegramChatResult.BotNotAdmin, useCase(-10042))
        assertEquals(null, store.chatId)

        telegram.member = TelegramBotApiResult.Ok(TelegramChatMemberStatus.OWNER)
        assertEquals(SelectTelegramChatResult.Selected(-10042), useCase(-10042))
        assertEquals(-10042, store.chatId)
    }

    private fun token() = TelegramBotToken.parse("123456:abcdefghijklmnopqrstuvwxyzABCDE")!!
}

private class FakeNotificationSettingsStore(
    var token: StoredTelegramBotToken = StoredTelegramBotToken.Absent,
    var chatId: Long? = null,
    var enabled: Boolean = false,
) : NotificationSettingsStore
{
    override suspend fun telegramBotToken() = token
    override suspend fun setTelegramBotToken(token: TelegramBotToken) { this.token = StoredTelegramBotToken.Present(token) }
    override suspend fun clearTelegramBotToken() { token = StoredTelegramBotToken.Absent }
    override suspend fun selectedTelegramChatId() = chatId
    override suspend fun setSelectedTelegramChatId(chatId: Long) { this.chatId = chatId }
    override suspend fun clearSelectedTelegramChatId() { chatId = null }
    override suspend fun telegramEnabled() = enabled
    override suspend fun setTelegramEnabled(enabled: Boolean) { this.enabled = enabled }
    override suspend fun lastNotifiedClientVersion(clientKey: String) = null
    override suspend fun setLastNotifiedClientVersion(clientKey: String, version: String) = Unit
}

private class FakeTelegramBotApi(
    var getMe: TelegramBotApiResult<TelegramBot> = TelegramBotApiResult.Ok(TelegramBot(7, "toolkit_bot")),
    var updates: TelegramBotApiResult<List<TelegramChat>> = TelegramBotApiResult.Ok(emptyList()),
    var member: TelegramBotApiResult<TelegramChatMemberStatus> = TelegramBotApiResult.Ok(TelegramChatMemberStatus.ADMINISTRATOR),
) : TelegramBotApi
{
    override suspend fun getMe(token: TelegramBotToken) = getMe
    override suspend fun getUpdates(token: TelegramBotToken) = updates
    override suspend fun getChatMember(token: TelegramBotToken, chatId: Long, userId: Long) = member
    override suspend fun sendMessage(token: TelegramBotToken, chatId: Long, text: String) = TelegramBotApiResult.Ok(Unit)
}
