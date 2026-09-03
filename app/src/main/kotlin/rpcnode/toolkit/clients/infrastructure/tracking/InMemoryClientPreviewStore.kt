package rpcnode.toolkit.clients.infrastructure.tracking

import java.util.concurrent.ConcurrentHashMap
import rpcnode.toolkit.clients.application.ClientPreviewStore
import rpcnode.toolkit.clients.application.ClientProgramKey
import rpcnode.toolkit.clients.domain.model.ClientVersionPin

class InMemoryClientPreviewStore : ClientPreviewStore
{
    private val byKey = ConcurrentHashMap<ClientProgramKey, ClientVersionPin>()

    override fun get(key: ClientProgramKey): ClientVersionPin? = byKey[key]

    override fun put(key: ClientProgramKey, pin: ClientVersionPin)
    {
        byKey[key] = pin
    }

    override fun clear(key: ClientProgramKey)
    {
        byKey.remove(key)
    }
}
