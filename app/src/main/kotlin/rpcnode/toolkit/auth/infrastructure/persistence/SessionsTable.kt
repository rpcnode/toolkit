package rpcnode.toolkit.auth.infrastructure.persistence

import org.jetbrains.exposed.sql.Table

object SessionsTable : Table("sessions")
{
    val token = varchar("token", 128)
    val username = varchar("username", 255)
    val expiresAt = varchar("expires_at", 64)

    override val primaryKey = PrimaryKey(token)
}
