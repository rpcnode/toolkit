package rpcnode.toolkit.servers

import rpcnode.toolkit.servers.domain.model.SERVER_REMOVE_STATUS_REMOVING
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

class FakeServerRepository(
    seed: List<Server> = emptyList(),
) : ServerRepository
{
    private val byId = seed.associateByTo(mutableMapOf()) { it.id }

    override suspend fun list(): List<Server> =
        byId.values.filter { !it.isDeleted() }.sortedBy { it.displayName() }

    override suspend fun listRemoving(): List<Server> = byId.values.filter { it.isRemoving() }

    override suspend fun find(id: ServerId): Server? = byId[id]

    override suspend fun findByAgentKey(token: String): Server?
    {
        val key = token.trim()
        if (key.isEmpty())
        {
            return null
        }
        return byId.values.firstOrNull { it.agentKey == key && !it.isDeleted() }
    }

    override suspend fun insert(server: Server)
    {
        byId[server.id] = server
    }

    override suspend fun update(server: Server)
    {
        val current = byId[server.id] ?: return
        if (current.isDeleted())
        {
            return
        }
        byId[server.id] = server
    }

    override suspend fun setAgentVersion(id: ServerId, version: String, now: String)
    {
        val ver = version.trim()
        if (ver.isEmpty())
        {
            return
        }
        val current = byId[id] ?: return
        if (current.isDeleted())
        {
            return
        }
        byId[id] = current.copy(agentVersion = ver, updatedAt = now)
    }

    override suspend fun markRemoving(id: ServerId, now: String)
    {
        val current = byId[id] ?: return
        if (current.isDeleted())
        {
            return
        }
        byId[id] = current.copy(removeStatus = SERVER_REMOVE_STATUS_REMOVING, updatedAt = now)
    }

    override suspend fun markDeleted(id: ServerId, now: String)
    {
        val current = byId[id] ?: return
        byId[id] = current.copy(deletedAt = now, removeStatus = "", updatedAt = now)
    }
}
