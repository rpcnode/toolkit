package rpcnode.toolkit.nodes.domain.repository

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.servers.domain.model.ServerId

interface NodeRepository
{
    suspend fun list(): List<Node>
    suspend fun findById(id: NodeId): Node?
    suspend fun findByServerNetworkEnv(serverId: ServerId, network: NetworkId, env: EnvId): Node?
    suspend fun listOnServer(serverId: ServerId): List<Node>
    /** Duplicate unique key is a result, not an exception. Other SQLite failures still throw. */
    suspend fun insert(node: Node): NodeInsertResult
    /** True if a row was removed. */
    suspend fun delete(id: NodeId): Boolean

    suspend fun saveDiskLayout(id: NodeId, diskLayoutJson: String, updatedAt: String): Boolean

    suspend fun saveInstallOptions(id: NodeId, installOptionsJson: String, updatedAt: String): Boolean

    suspend fun updateStatus(id: NodeId, status: NodeStatus, updatedAt: String): Boolean

    suspend fun updateHeight(
        id: NodeId,
        height: Long,
        heightAt: String,
        updatedAt: String,
        networkHeight: Long? = null,
        syncPct: Double? = null,
    ): Boolean

    suspend fun updateSizeOnDisk(id: NodeId, sizeOnDisk: Long, updatedAt: String): Boolean

    suspend fun updateClientVersion(
        id: NodeId,
        clientVersion: String,
        clientLatest: String,
        clientUpdateAvailable: Boolean,
        updatedAt: String,
    ): Boolean
}

sealed interface NodeInsertResult
{
    data object Ok : NodeInsertResult
    data object Duplicate : NodeInsertResult
}
