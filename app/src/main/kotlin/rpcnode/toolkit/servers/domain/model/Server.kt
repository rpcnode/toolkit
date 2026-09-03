package rpcnode.toolkit.servers.domain.model

const val SERVER_REMOVE_STATUS_REMOVING = "removing"

data class Server(
    val id: ServerId,
    val name: String,
    val agentUrl: String,
    val agentKey: String = "",
    val os: String = "",
    val arch: String = "",
    val osPretty: String = "",
    val agentVersion: String = "",
    val createdAt: String,
    val updatedAt: String,
    val removeStatus: String = "",
    val deletedAt: String = "",
)
{
    fun displayName(): String = name.trim().ifEmpty { id.value }

    fun isDeleted(): Boolean = deletedAt.isNotBlank()

    fun isRemoving(): Boolean = removeStatus == SERVER_REMOVE_STATUS_REMOVING && !isDeleted()

    fun isActive(): Boolean = !isDeleted() && !isRemoving()
}
