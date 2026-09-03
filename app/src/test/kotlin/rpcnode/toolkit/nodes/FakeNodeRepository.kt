package rpcnode.toolkit.nodes

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeInsertResult
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.model.ServerId

class FakeNodeRepository(
    seed: List<Node> = emptyList(),
    private val forcedInsert: NodeInsertResult? = null,
) : NodeRepository
{
    private val byId = seed.associateByTo(mutableMapOf()) { it.id }

    /** First N lookups pretend the row is missing (race with a concurrent insert). */
    var findMisses = 0

    override suspend fun list(): List<Node> = byId.values.sortedBy { it.id.value }

    override suspend fun findById(id: NodeId): Node? = byId[id]

    override suspend fun findByServerNetworkEnv(serverId: ServerId, network: NetworkId, env: EnvId): Node?
    {
        if (findMisses > 0)
        {
            findMisses--
            return null
        }
        return byId.values.firstOrNull { it.serverId == serverId && it.network == network && it.env == env }
    }

    override suspend fun listOnServer(serverId: ServerId): List<Node> =
        byId.values.filter { it.serverId == serverId }

    override suspend fun insert(node: Node): NodeInsertResult
    {
        if (forcedInsert != null)
        {
            return forcedInsert
        }
        if (byId.containsKey(node.id) ||
            byId.values.any { it.serverId == node.serverId && it.network == node.network && it.env == node.env }
        )
        {
            return NodeInsertResult.Duplicate
        }
        byId[node.id] = node
        return NodeInsertResult.Ok
    }

    override suspend fun delete(id: NodeId): Boolean = byId.remove(id) != null

    override suspend fun saveDiskLayout(id: NodeId, diskLayoutJson: String, updatedAt: String): Boolean
    {
        val node = byId[id] ?: return false
        byId[id] = node.copy(diskLayoutJson = diskLayoutJson, updatedAt = updatedAt)
        return true
    }

    override suspend fun saveInstallOptions(id: NodeId, installOptionsJson: String, updatedAt: String): Boolean
    {
        val node = byId[id] ?: return false
        byId[id] = node.copy(installOptionsJson = installOptionsJson, updatedAt = updatedAt)
        return true
    }

    override suspend fun updateStatus(id: NodeId, status: NodeStatus, updatedAt: String): Boolean
    {
        val node = byId[id] ?: return false
        byId[id] = node.copy(status = status, updatedAt = updatedAt)
        return true
    }

    override suspend fun updateHeight(
        id: NodeId,
        height: Long,
        heightAt: String,
        updatedAt: String,
        networkHeight: Long?,
        syncPct: Double?,
    ): Boolean
    {
        val node = byId[id] ?: return false
        byId[id] = node.copy(
            height = height,
            heightAt = heightAt,
            networkHeight = networkHeight ?: node.networkHeight,
            syncPct = syncPct ?: node.syncPct,
            updatedAt = updatedAt,
        )
        return true
    }

    override suspend fun updateSizeOnDisk(id: NodeId, sizeOnDisk: Long, updatedAt: String): Boolean
    {
        val node = byId[id] ?: return false
        byId[id] = node.copy(sizeOnDisk = sizeOnDisk, updatedAt = updatedAt)
        return true
    }

    override suspend fun updateClientVersion(
        id: NodeId,
        clientVersion: String,
        clientLatest: String,
        clientUpdateAvailable: Boolean,
        updatedAt: String,
    ): Boolean
    {
        val node = byId[id] ?: return false
        byId[id] = node.copy(
            clientVersion = clientVersion,
            clientLatest = clientLatest,
            clientUpdateAvailable = clientUpdateAvailable,
            updatedAt = updatedAt,
        )
        return true
    }
}
