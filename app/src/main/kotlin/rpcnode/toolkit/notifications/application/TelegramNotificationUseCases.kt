package rpcnode.toolkit.notifications.application

import rpcnode.toolkit.notifications.domain.model.StoredTelegramBotToken
import rpcnode.toolkit.notifications.domain.model.TelegramBot
import rpcnode.toolkit.notifications.domain.model.TelegramBotToken
import rpcnode.toolkit.notifications.domain.model.TelegramChat
import rpcnode.toolkit.notifications.domain.repository.NotificationSettingsStore

data class TelegramNotificationSettings(
    val token: StoredTelegramBotToken,
    val chatId: Long?,
    val enabled: Boolean,
)

class GetTelegramNotificationSettingsUseCase(
    private val store: NotificationSettingsStore,
)
{
    suspend operator fun invoke() = TelegramNotificationSettings(
        token = store.telegramBotToken(),
        chatId = store.selectedTelegramChatId(),
        enabled = store.telegramEnabled(),
    )
}

class SetTelegramNotificationsEnabledUseCase(
    private val store: NotificationSettingsStore,
)
{
    suspend operator fun invoke(enabled: Boolean): SetTelegramNotificationsEnabledResult
    {
        if (enabled && store.selectedTelegramChatId() == null)
        {
            return SetTelegramNotificationsEnabledResult.ChatMissing
        }
        store.setTelegramEnabled(enabled)
        return SetTelegramNotificationsEnabledResult.Updated
    }
}

sealed interface SetTelegramNotificationsEnabledResult
{
    data object Updated : SetTelegramNotificationsEnabledResult
    data object ChatMissing : SetTelegramNotificationsEnabledResult
}

class ClearTelegramBotUseCase(
    private val store: NotificationSettingsStore,
)
{
    suspend operator fun invoke()
    {
        store.clearTelegramBotToken()
        store.clearSelectedTelegramChatId()
        store.setTelegramEnabled(false)
    }
}

class ConfigureTelegramBotUseCase(
    private val store: NotificationSettingsStore,
    private val telegram: TelegramBotApi,
)
{
    suspend operator fun invoke(rawToken: String): ConfigureTelegramBotResult
    {
        val token = TelegramBotToken.parse(rawToken) ?: return ConfigureTelegramBotResult.InvalidFormat
        return when (val result = telegram.getMe(token))
        {
            is TelegramBotApiResult.Ok ->
            {
                store.setTelegramBotToken(token)
                store.clearSelectedTelegramChatId()
                store.setTelegramEnabled(false)
                ConfigureTelegramBotResult.Configured(result.value)
            }
            TelegramBotApiResult.InvalidToken -> ConfigureTelegramBotResult.InvalidToken
            is TelegramBotApiResult.Rejected -> ConfigureTelegramBotResult.Rejected(result.message)
            is TelegramBotApiResult.Unavailable -> ConfigureTelegramBotResult.Unavailable(result.message)
        }
    }
}

sealed interface ConfigureTelegramBotResult
{
    data class Configured(val bot: TelegramBot) : ConfigureTelegramBotResult
    data object InvalidFormat : ConfigureTelegramBotResult
    data object InvalidToken : ConfigureTelegramBotResult
    data class Rejected(val message: String) : ConfigureTelegramBotResult
    data class Unavailable(val message: String) : ConfigureTelegramBotResult
}

class DiscoverTelegramChatsUseCase(
    private val store: NotificationSettingsStore,
    private val telegram: TelegramBotApi,
)
{
    suspend operator fun invoke(): DiscoverTelegramChatsResult
    {
        val token = when (val stored = store.telegramBotToken())
        {
            StoredTelegramBotToken.Absent -> return DiscoverTelegramChatsResult.TokenMissing
            StoredTelegramBotToken.Corrupt -> return DiscoverTelegramChatsResult.TokenCorrupt
            is StoredTelegramBotToken.Present -> stored.token
        }
        return when (val result = telegram.getUpdates(token))
        {
            is TelegramBotApiResult.Ok -> DiscoverTelegramChatsResult.Chats(
                result.value.distinctBy { it.id }.sortedWith(compareBy({ it.title.lowercase() }, { it.id })),
            )
            TelegramBotApiResult.InvalidToken -> DiscoverTelegramChatsResult.InvalidToken
            is TelegramBotApiResult.Rejected -> DiscoverTelegramChatsResult.Rejected(result.message)
            is TelegramBotApiResult.Unavailable -> DiscoverTelegramChatsResult.Unavailable(result.message)
        }
    }
}

sealed interface DiscoverTelegramChatsResult
{
    data class Chats(val chats: List<TelegramChat>) : DiscoverTelegramChatsResult
    data object TokenMissing : DiscoverTelegramChatsResult
    data object TokenCorrupt : DiscoverTelegramChatsResult
    data object InvalidToken : DiscoverTelegramChatsResult
    data class Rejected(val message: String) : DiscoverTelegramChatsResult
    data class Unavailable(val message: String) : DiscoverTelegramChatsResult
}

class SelectTelegramChatUseCase(
    private val store: NotificationSettingsStore,
    private val telegram: TelegramBotApi,
)
{
    suspend operator fun invoke(chatId: Long): SelectTelegramChatResult
    {
        val token = when (val stored = store.telegramBotToken())
        {
            StoredTelegramBotToken.Absent -> return SelectTelegramChatResult.TokenMissing
            StoredTelegramBotToken.Corrupt -> return SelectTelegramChatResult.TokenCorrupt
            is StoredTelegramBotToken.Present -> stored.token
        }
        val bot = when (val result = telegram.getMe(token))
        {
            is TelegramBotApiResult.Ok -> result.value
            TelegramBotApiResult.InvalidToken -> return SelectTelegramChatResult.InvalidToken
            is TelegramBotApiResult.Rejected -> return SelectTelegramChatResult.Rejected(result.message)
            is TelegramBotApiResult.Unavailable -> return SelectTelegramChatResult.Unavailable(result.message)
        }
        return when (val result = telegram.getChatMember(token, chatId, bot.id))
        {
            is TelegramBotApiResult.Ok ->
            {
                if (!result.value.canManageNotifications)
                {
                    SelectTelegramChatResult.BotNotAdmin
                }
                else
                {
                    store.setSelectedTelegramChatId(chatId)
                    SelectTelegramChatResult.Selected(chatId)
                }
            }
            TelegramBotApiResult.InvalidToken -> SelectTelegramChatResult.InvalidToken
            is TelegramBotApiResult.Rejected -> SelectTelegramChatResult.Rejected(result.message)
            is TelegramBotApiResult.Unavailable -> SelectTelegramChatResult.Unavailable(result.message)
        }
    }
}

sealed interface SelectTelegramChatResult
{
    data class Selected(val chatId: Long) : SelectTelegramChatResult
    data object TokenMissing : SelectTelegramChatResult
    data object TokenCorrupt : SelectTelegramChatResult
    data object InvalidToken : SelectTelegramChatResult
    data object BotNotAdmin : SelectTelegramChatResult
    data class Rejected(val message: String) : SelectTelegramChatResult
    data class Unavailable(val message: String) : SelectTelegramChatResult
}

class SendTelegramTestUseCase(
    private val store: NotificationSettingsStore,
    private val telegram: TelegramBotApi,
)
{
    suspend operator fun invoke(): SendTelegramTestResult
    {
        val token = when (val stored = store.telegramBotToken())
        {
            StoredTelegramBotToken.Absent -> return SendTelegramTestResult.TokenMissing
            StoredTelegramBotToken.Corrupt -> return SendTelegramTestResult.TokenCorrupt
            is StoredTelegramBotToken.Present -> stored.token
        }
        val chatId = store.selectedTelegramChatId() ?: return SendTelegramTestResult.ChatMissing
        return when (val result = telegram.sendMessage(token, chatId, "RPC Node Toolkit Telegram notifications are connected."))
        {
            is TelegramBotApiResult.Ok -> SendTelegramTestResult.Sent
            TelegramBotApiResult.InvalidToken -> SendTelegramTestResult.InvalidToken
            is TelegramBotApiResult.Rejected -> SendTelegramTestResult.Rejected(result.message)
            is TelegramBotApiResult.Unavailable -> SendTelegramTestResult.Unavailable(result.message)
        }
    }
}

sealed interface SendTelegramTestResult
{
    data object Sent : SendTelegramTestResult
    data object TokenMissing : SendTelegramTestResult
    data object TokenCorrupt : SendTelegramTestResult
    data object ChatMissing : SendTelegramTestResult
    data object InvalidToken : SendTelegramTestResult
    data class Rejected(val message: String) : SendTelegramTestResult
    data class Unavailable(val message: String) : SendTelegramTestResult
}
