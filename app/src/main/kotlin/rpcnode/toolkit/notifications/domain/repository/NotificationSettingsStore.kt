package rpcnode.toolkit.notifications.domain.repository

import rpcnode.toolkit.notifications.domain.model.StoredTelegramBotToken
import rpcnode.toolkit.notifications.domain.model.TelegramBotToken

interface NotificationSettingsStore
{
    suspend fun telegramBotToken(): StoredTelegramBotToken
    suspend fun setTelegramBotToken(token: TelegramBotToken)
    suspend fun clearTelegramBotToken()
    suspend fun selectedTelegramChatId(): Long?
    suspend fun setSelectedTelegramChatId(chatId: Long)
    suspend fun clearSelectedTelegramChatId()
    suspend fun telegramEnabled(): Boolean
    suspend fun setTelegramEnabled(enabled: Boolean)
    suspend fun lastNotifiedClientVersion(clientKey: String): String?
    suspend fun setLastNotifiedClientVersion(clientKey: String, version: String)
}
