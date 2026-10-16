package rpcnode.toolkit.proxies

import rpcnode.toolkit.proxies.domain.model.Proxy
import rpcnode.toolkit.proxies.domain.model.ProxyId
import rpcnode.toolkit.proxies.domain.repository.ProxyRepository

class FakeProxyRepository : ProxyRepository
{
    private val byId = linkedMapOf<ProxyId, Proxy>()

    override suspend fun list(): List<Proxy> = byId.values.toList()

    override suspend fun find(id: ProxyId): Proxy? = byId[id]

    override suspend fun save(proxy: Proxy)
    {
        byId[proxy.id] = proxy
    }

    override suspend fun delete(id: ProxyId): Boolean = byId.remove(id) != null
}
