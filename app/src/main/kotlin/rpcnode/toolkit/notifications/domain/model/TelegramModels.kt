package rpcnode.toolkit.notifications.domain.model

data class TelegramBot(
    val id: Long,
    val username: String?,
)

data class TelegramChat(
    val id: Long,
    val type: String,
    val title: String,
    val username: String?,
)

enum class TelegramChatMemberStatus
{
    ADMINISTRATOR,
    CREATOR,
    OWNER,
    MEMBER,
    RESTRICTED,
    LEFT,
    KICKED,
    UNKNOWN,
    ;

    val canManageNotifications: Boolean
        get() = this == ADMINISTRATOR || this == CREATOR || this == OWNER
}
