package rpcnode.toolkit.servers.domain.repository

import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

interface ServerRepository
{
    /** Live registry rows (not soft-deleted). Includes hosts still being removed. */
    suspend fun list(): List<Server>
    suspend fun listRemoving(): List<Server>
    suspend fun find(id: ServerId): Server?
    suspend fun findByAgentKey(token: String): Server?
    suspend fun insert(server: Server)
    suspend fun update(server: Server)
    suspend fun setAgentVersion(id: ServerId, version: String, now: String)
    suspend fun markRemoving(id: ServerId, now: String)
    suspend fun markDeleted(id: ServerId, now: String)
}
