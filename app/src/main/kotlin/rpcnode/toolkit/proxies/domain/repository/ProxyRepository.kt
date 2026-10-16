package rpcnode.toolkit.proxies.domain.repository

import rpcnode.toolkit.proxies.domain.model.Proxy
import rpcnode.toolkit.proxies.domain.model.ProxyId

/** Global proxy list (Settings → Proxies). Nodes reference entries by id. */
interface ProxyRepository
{
    suspend fun list(): List<Proxy>
    suspend fun find(id: ProxyId): Proxy?
    /** Insert or replace by id. */
    suspend fun save(proxy: Proxy)
    /** True if an entry was removed. */
    suspend fun delete(id: ProxyId): Boolean
}
