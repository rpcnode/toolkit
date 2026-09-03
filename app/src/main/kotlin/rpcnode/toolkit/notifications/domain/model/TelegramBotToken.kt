package rpcnode.toolkit.notifications.domain.model

class TelegramBotToken private constructor(
    val value: String,
)
{
    companion object
    {
        private val FORMAT = Regex("""^\d{5,20}:[A-Za-z0-9_-]{20,}$""")

        fun parse(raw: String?): TelegramBotToken? =
            raw?.trim()?.takeIf { FORMAT.matches(it) }?.let(::TelegramBotToken)
    }
}

sealed interface StoredTelegramBotToken
{
    data object Absent : StoredTelegramBotToken
    data object Corrupt : StoredTelegramBotToken
    data class Present(val token: TelegramBotToken) : StoredTelegramBotToken
}
