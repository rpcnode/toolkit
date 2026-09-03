package rpcnode.toolkit.nodes.infrastructure.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.transaction
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeInsertResult
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class SqliteNodeRepository(
    private val db: ToolkitDatabase,
) : NodeRepository
{
    override suspend fun list(): List<Node> = withContext(Dispatchers.IO) {
        transaction(db.database) {
            NodesTable.selectAll()
                .map { it.toDomain() }
                .sortedWith(compareBy({ it.network.value }, { it.env.value }, { it.id.value }))
        }
    }

    override suspend fun findById(id: NodeId): Node? = withContext(Dispatchers.IO) {
        transaction(db.database) {
            NodesTable.selectAll()
                .where { NodesTable.id eq id.value }
                .singleOrNull()
                ?.toDomain()
        }
    }

    override suspend fun findByServerNetworkEnv(
        serverId: ServerId,
        network: NetworkId,
        env: EnvId,
    ): Node? = withContext(Dispatchers.IO) {
        transaction(db.database) {
            NodesTable.selectAll()
                .where {
                    (NodesTable.serverId eq serverId.value) and
                        (NodesTable.network eq network.value) and
                        (NodesTable.env eq env.value)
                }
                .singleOrNull()
                ?.toDomain()
        }
    }

    override suspend fun listOnServer(serverId: ServerId): List<Node> = withContext(Dispatchers.IO) {
        transaction(db.database) {
            NodesTable.selectAll()
                .where { NodesTable.serverId eq serverId.value }
                .map { it.toDomain() }
        }
    }

    override suspend fun insert(node: Node): NodeInsertResult = withContext(Dispatchers.IO) {
        try
        {
            transaction(db.database) {
                NodesTable.insert {
                    it[id] = node.id.value
                    it[serverId] = node.serverId.value
                    it[name] = node.name
                    it[network] = node.network.value
                    it[env] = node.env.value
                    it[publicPort] = node.publicPort
                    it[agentPort] = node.agentPort
                    it[nodeHttpPort] = node.nodeHttpPort
                    it[p2pPort] = node.p2pPort
                    it[status] = node.status.value
                    it[clientVersion] = node.clientVersion
                    it[clientLatest] = node.clientLatest
                    it[clientUpdateAvailable] = if (node.clientUpdateAvailable) 1 else 0
                    it[createdAt] = node.createdAt
                    it[updatedAt] = node.updatedAt
                }
            }
            NodeInsertResult.Ok
        }
        catch (e: Exception)
        {
            if (uniqueConstraintFailed(e))
            {
                NodeInsertResult.Duplicate
            }
            else
            {
                throw e
            }
        }
    }

    override suspend fun delete(id: NodeId): Boolean = withContext(Dispatchers.IO) {
        transaction(db.database) {
            NodesTable.deleteWhere { SqlExpressionBuilder.run { NodesTable.id eq id.value } }
        } > 0
    }

    override suspend fun saveDiskLayout(id: NodeId, diskLayoutJson: String, updatedAt: String): Boolean =
        withContext(Dispatchers.IO) {
            transaction(db.database) {
                NodesTable.update({ NodesTable.id eq id.value }) {
                    it[NodesTable.diskLayoutJson] = diskLayoutJson
                    it[NodesTable.updatedAt] = updatedAt
                }
            } > 0
        }

    override suspend fun saveInstallOptions(id: NodeId, installOptionsJson: String, updatedAt: String): Boolean =
        withContext(Dispatchers.IO) {
            transaction(db.database) {
                NodesTable.update({ NodesTable.id eq id.value }) {
                    it[NodesTable.installOptionsJson] = installOptionsJson
                    it[NodesTable.updatedAt] = updatedAt
                }
            } > 0
        }

    override suspend fun updateStatus(id: NodeId, status: NodeStatus, updatedAt: String): Boolean =
        withContext(Dispatchers.IO) {
            transaction(db.database) {
                NodesTable.update({ NodesTable.id eq id.value }) {
                    it[NodesTable.status] = status.value
                    it[NodesTable.updatedAt] = updatedAt
                }
            } > 0
        }

    override suspend fun updateHeight(
        id: NodeId,
        height: Long,
        heightAt: String,
        updatedAt: String,
        networkHeight: Long?,
        syncPct: Double?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            transaction(db.database) {
                NodesTable.update({ NodesTable.id eq id.value }) {
                    it[NodesTable.height] = height
                    it[NodesTable.heightAt] = heightAt
                    if (networkHeight != null)
                    {
                        it[NodesTable.networkHeight] = networkHeight
                    }
                    if (syncPct != null)
                    {
                        it[NodesTable.syncPct] = syncPct
                    }
                    it[NodesTable.updatedAt] = updatedAt
                }
            } > 0
        }

    override suspend fun updateSizeOnDisk(id: NodeId, sizeOnDisk: Long, updatedAt: String): Boolean =
        withContext(Dispatchers.IO) {
            transaction(db.database) {
                NodesTable.update({ NodesTable.id eq id.value }) {
                    it[NodesTable.sizeOnDisk] = sizeOnDisk
                    it[NodesTable.updatedAt] = updatedAt
                }
            } > 0
        }

    override suspend fun updateClientVersion(
        id: NodeId,
        clientVersion: String,
        clientLatest: String,
        clientUpdateAvailable: Boolean,
        updatedAt: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            transaction(db.database) {
                NodesTable.update({ NodesTable.id eq id.value }) {
                    it[NodesTable.clientVersion] = clientVersion
                    it[NodesTable.clientLatest] = clientLatest
                    it[NodesTable.clientUpdateAvailable] = if (clientUpdateAvailable) 1 else 0
                    it[NodesTable.updatedAt] = updatedAt
                }
            } > 0
        }

    private fun ResultRow.toDomain() = Node(
        id = NodeId.parse(this[NodesTable.id]) ?: error("invalid node id"),
        serverId = ServerId.parse(this[NodesTable.serverId]) ?: error("invalid server id on node"),
        name = this[NodesTable.name],
        network = NetworkId.parse(this[NodesTable.network]) ?: error("invalid network on node"),
        env = EnvId.parse(this[NodesTable.env]) ?: error("invalid env on node"),
        publicPort = this[NodesTable.publicPort],
        agentPort = this[NodesTable.agentPort],
        nodeHttpPort = this[NodesTable.nodeHttpPort],
        p2pPort = this[NodesTable.p2pPort],
        status = NodeStatus.parse(this[NodesTable.status]),
        diskLayoutJson = this[NodesTable.diskLayoutJson],
        installOptionsJson = this[NodesTable.installOptionsJson],
        height = this[NodesTable.height],
        heightAt = this[NodesTable.heightAt],
        networkHeight = this[NodesTable.networkHeight],
        syncPct = this[NodesTable.syncPct],
        sizeOnDisk = this[NodesTable.sizeOnDisk],
        clientVersion = this[NodesTable.clientVersion],
        clientLatest = this[NodesTable.clientLatest],
        clientUpdateAvailable = this[NodesTable.clientUpdateAvailable] != 0,
        createdAt = this[NodesTable.createdAt],
        updatedAt = this[NodesTable.updatedAt],
    )
}

private fun uniqueConstraintFailed(e: Exception): Boolean
{
    var cur: Throwable? = e
    while (cur != null)
    {
        if (cur.message.orEmpty().contains("UNIQUE constraint failed", ignoreCase = true))
        {
            return true
        }
        cur = cur.cause
    }
    return false
}
