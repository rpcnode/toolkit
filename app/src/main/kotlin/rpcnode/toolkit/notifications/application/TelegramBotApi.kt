package rpcnode.toolkit.notifications.application

import rpcnode.toolkit.notifications.domain.model.TelegramBot
import rpcnode.toolkit.notifications.domain.model.TelegramBotToken
import rpcnode.toolkit.notifications.domain.model.TelegramChat
import rpcnode.toolkit.notifications.domain.model.TelegramChatMemberStatus

interface TelegramBotApi
{
    suspend fun getMe(token: TelegramBotToken): TelegramBotApiResult<TelegramBot>
    suspend fun getUpdates(token: TelegramBotToken): TelegramBotApiResult<List<TelegramChat>>
    suspend fun getChatMember(
        token: TelegramBotToken,
        chatId: Long,
        userId: Long,
    ): TelegramBotApiResult<TelegramChatMemberStatus>

    suspend fun sendMessage(
        token: TelegramBotToken,
        chatId: Long,
        text: String,
    ): TelegramBotApiResult<Unit>
}

sealed interface TelegramBotApiResult<out T>
{
    data class Ok<T>(val value: T) : TelegramBotApiResult<T>
    data object InvalidToken : TelegramBotApiResult<Nothing>
    data class Rejected(val message: String) : TelegramBotApiResult<Nothing>
    data class Unavailable(val message: String) : TelegramBotApiResult<Nothing>
}
