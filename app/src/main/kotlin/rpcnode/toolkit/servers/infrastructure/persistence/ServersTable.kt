package rpcnode.toolkit.servers.infrastructure.persistence

import org.jetbrains.exposed.sql.Table

object ServersTable : Table("servers")
{
    val id = varchar("id", 64)
    val name = varchar("name", 255).default("")
    val env = varchar("env", 64).default("")
    val network = varchar("network", 255).default("")
    val agentUrl = varchar("agent_url", 512).default("")
    val agentKey = varchar("agent_key", 512).default("")
    val os = varchar("os", 64).default("")
    val arch = varchar("arch", 64).default("")
    val osPretty = varchar("os_pretty", 255).default("")
    val agentVersion = varchar("agent_version", 64).default("")
    val agentBuild = varchar("agent_build", 255).default("")
    val createdAt = varchar("created_at", 64)
    val updatedAt = varchar("updated_at", 64)
    val removeStatus = varchar("remove_status", 32).default("")
    val deletedAt = varchar("deleted_at", 64).default("")

    override val primaryKey = PrimaryKey(id)
}
