package rpcnode.toolkit.notifications.infrastructure.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.notifications.domain.model.StoredTelegramBotToken
import rpcnode.toolkit.notifications.domain.model.TelegramBotToken
import rpcnode.toolkit.notifications.domain.repository.NotificationSettingsStore
import rpcnode.toolkit.settings.infrastructure.persistence.AesGcmSecretBox
import rpcnode.toolkit.settings.infrastructure.persistence.getSetting
import rpcnode.toolkit.settings.infrastructure.persistence.setSetting
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class SqliteNotificationSettingsStore(
    private val db: ToolkitDatabase,
    private val secrets: AesGcmSecretBox,
) : NotificationSettingsStore
{
    private val lock = Any()

    override suspend fun telegramBotToken(): StoredTelegramBotToken = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val encrypted = db.getSetting(KEY_TOKEN).orEmpty().trim()
            if (encrypted.isEmpty())
            {
                return@synchronized StoredTelegramBotToken.Absent
            }
            val plain = try
            {
                secrets.decrypt(encrypted)
            }
            catch (_: Exception)
            {
                return@synchronized StoredTelegramBotToken.Corrupt
            }
            TelegramBotToken.parse(plain)?.let(StoredTelegramBotToken::Present)
                ?: StoredTelegramBotToken.Corrupt
        }
    }

    override suspend fun setTelegramBotToken(token: TelegramBotToken) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.setSetting(KEY_TOKEN, secrets.encrypt(token.value))
        }
    }

    override suspend fun clearTelegramBotToken() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.setSetting(KEY_TOKEN, "")
        }
    }

    override suspend fun selectedTelegramChatId(): Long? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.getSetting(KEY_CHAT_ID)?.trim()?.toLongOrNull()
        }
    }

    override suspend fun setSelectedTelegramChatId(chatId: Long) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.setSetting(KEY_CHAT_ID, chatId.toString())
        }
    }

    override suspend fun clearSelectedTelegramChatId() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.setSetting(KEY_CHAT_ID, "")
        }
    }

    override suspend fun telegramEnabled(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.getSetting(KEY_ENABLED).equals("true", ignoreCase = true)
        }
    }

    override suspend fun setTelegramEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.setSetting(KEY_ENABLED, enabled.toString())
        }
    }

    override suspend fun lastNotifiedClientVersion(clientKey: String): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.getSetting("$KEY_CLIENT_VERSION.$clientKey")?.trim()?.ifEmpty { null }
        }
    }

    override suspend fun setLastNotifiedClientVersion(clientKey: String, version: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.setSetting("$KEY_CLIENT_VERSION.$clientKey", version)
        }
    }

    private companion object
    {
        const val KEY_TOKEN = "notifications.telegram.token_enc"
        const val KEY_CHAT_ID = "notifications.telegram.chat_id"
        const val KEY_ENABLED = "notifications.telegram.enabled"
        const val KEY_CLIENT_VERSION = "notifications.telegram.client_version"
    }
}
