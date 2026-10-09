package rpcnode.toolkit.servers.infrastructure.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import rpcnode.toolkit.servers.domain.model.SERVER_REMOVE_STATUS_REMOVING
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class SqliteServerRepository(
    private val db: ToolkitDatabase,
) : ServerRepository
{
    override suspend fun list(): List<Server> = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ServersTable.selectAll()
                .where { ServersTable.deletedAt eq "" }
                .map { it.toDomain() }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName() })
        }
    }

    override suspend fun listRemoving(): List<Server> = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ServersTable.selectAll()
                .where {
                    (ServersTable.deletedAt eq "") and (ServersTable.removeStatus eq SERVER_REMOVE_STATUS_REMOVING)
                }
                .map { it.toDomain() }
        }
    }

    override suspend fun find(id: ServerId): Server? = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ServersTable.selectAll()
                .where { ServersTable.id eq id.value }
                .singleOrNull()
                ?.toDomain()
        }
    }

    override suspend fun findByAgentKey(token: String): Server? = withContext(Dispatchers.IO) {
        val key = token.trim()
        if (key.isEmpty())
        {
            return@withContext null
        }
        transaction(db.database) {
            ServersTable.selectAll()
                .where { (ServersTable.agentKey eq key) and (ServersTable.deletedAt eq "") }
                .limit(1)
                .singleOrNull()
                ?.toDomain()
        }
    }

    override suspend fun insert(server: Server) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ServersTable.insert {
                it[id] = server.id.value
                it[name] = server.name
                it[agentUrl] = server.agentUrl
                it[agentKey] = server.agentKey
                it[os] = server.os
                it[arch] = server.arch
                it[osPretty] = server.osPretty
                it[agentVersion] = server.agentVersion
                it[createdAt] = server.createdAt
                it[updatedAt] = server.updatedAt
                it[removeStatus] = server.removeStatus
                it[deletedAt] = server.deletedAt
            }
            Unit
        }
    }

    override suspend fun update(server: Server) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ServersTable.update({ (ServersTable.id eq server.id.value) and (ServersTable.deletedAt eq "") }) {
                it[name] = server.name
                it[agentUrl] = server.agentUrl
                it[agentKey] = server.agentKey
                it[os] = server.os
                it[arch] = server.arch
                it[osPretty] = server.osPretty
                it[agentVersion] = server.agentVersion
                it[updatedAt] = server.updatedAt
                it[removeStatus] = server.removeStatus
            }
            Unit
        }
    }

    override suspend fun setAgentVersion(id: ServerId, version: String, now: String) = withContext(Dispatchers.IO) {
        val ver = version.trim()
        if (ver.isEmpty())
        {
            return@withContext
        }
        transaction(db.database) {
            ServersTable.update({ (ServersTable.id eq id.value) and (ServersTable.deletedAt eq "") }) {
                it[agentVersion] = ver
                it[updatedAt] = now
            }
            Unit
        }
    }

    override suspend fun markRemoving(id: ServerId, now: String) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ServersTable.update({ (ServersTable.id eq id.value) and (ServersTable.deletedAt eq "") }) {
                it[removeStatus] = SERVER_REMOVE_STATUS_REMOVING
                it[updatedAt] = now
            }
            Unit
        }
    }

    override suspend fun markDeleted(id: ServerId, now: String) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ServersTable.update({ ServersTable.id eq id.value }) {
                it[deletedAt] = now
                it[removeStatus] = ""
                it[updatedAt] = now
            }
            Unit
        }
    }

    private fun ResultRow.toDomain() = Server(
        id = ServerId.parse(this[ServersTable.id]) ?: error("invalid server id"),
        name = this[ServersTable.name],
        agentUrl = this[ServersTable.agentUrl],
        agentKey = this[ServersTable.agentKey],
        os = this[ServersTable.os],
        arch = this[ServersTable.arch],
        osPretty = this[ServersTable.osPretty],
        agentVersion = this[ServersTable.agentVersion],
        createdAt = this[ServersTable.createdAt],
        updatedAt = this[ServersTable.updatedAt],
        removeStatus = this[ServersTable.removeStatus],
        deletedAt = this[ServersTable.deletedAt],
    )
}
