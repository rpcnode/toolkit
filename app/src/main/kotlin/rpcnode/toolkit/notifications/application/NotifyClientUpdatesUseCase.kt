package rpcnode.toolkit.notifications.application

import rpcnode.toolkit.clients.application.probe.ProbeClientsResult
import rpcnode.toolkit.clients.application.probe.ProbeClientsUseCase
import rpcnode.toolkit.clients.domain.model.ClientStatus
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.notifications.domain.model.StoredTelegramBotToken
import rpcnode.toolkit.notifications.domain.repository.NotificationSettingsStore

class NotifyClientUpdatesUseCase(
    private val probeClients: ProbeClientsUseCase,
    private val clients: ClientVersionRepository,
    private val settings: NotificationSettingsStore,
    private val telegram: TelegramBotApi,
)
{
    suspend operator fun invoke(): NotifyClientUpdatesResult
    {
        if (probeClients() == ProbeClientsResult.TokenRequired)
        {
            return NotifyClientUpdatesResult.GitHubTokenMissing
        }
        if (!settings.telegramEnabled())
        {
            return NotifyClientUpdatesResult.NotificationsDisabled
        }
        val token = when (val stored = settings.telegramBotToken())
        {
            StoredTelegramBotToken.Absent -> return NotifyClientUpdatesResult.NotificationsDisabled
            StoredTelegramBotToken.Corrupt -> return NotifyClientUpdatesResult.NotificationsDisabled
            is StoredTelegramBotToken.Present -> stored.token
        }
        val chatId = settings.selectedTelegramChatId() ?: return NotifyClientUpdatesResult.NotificationsDisabled
        var sent = 0
        for (pin in clients.list().filter { it.status == ClientStatus.STALE })
        {
            val key = pin.clientKey()
            if (settings.lastNotifiedClientVersion(key) == pin.latestVersion)
            {
                continue
            }
            when (telegram.sendMessage(token, chatId, pin.notificationText()))
            {
                is TelegramBotApiResult.Ok ->
                {
                    settings.setLastNotifiedClientVersion(key, pin.latestVersion)
                    sent++
                }
                else -> Unit
            }
        }
        return NotifyClientUpdatesResult.Completed(sent)
    }
}

sealed interface NotifyClientUpdatesResult
{
    data class Completed(val sent: Int) : NotifyClientUpdatesResult
    data object GitHubTokenMissing : NotifyClientUpdatesResult
    data object NotificationsDisabled : NotifyClientUpdatesResult
}

private fun ClientVersionPin.clientKey() = "${network.value}.${env.value}.${program}"

private fun ClientVersionPin.notificationText() =
    "New client version available: ${network.value}/${env.value} $program\nInstalled: $currentVersion\nLatest: $latestVersion"
