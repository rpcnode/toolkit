package rpcnode.toolkit.clients.infrastructure.tracking

import java.util.concurrent.ConcurrentHashMap
import rpcnode.toolkit.clients.application.ClientDownloadProgress
import rpcnode.toolkit.clients.application.ClientDownloadTracker
import rpcnode.toolkit.clients.application.ClientProgramKey

class InMemoryClientDownloadTracker : ClientDownloadTracker
{
    private val byKey = ConcurrentHashMap<ClientProgramKey, ClientDownloadProgress>()

    override fun get(key: ClientProgramKey): ClientDownloadProgress? = byKey[key]

    override fun set(key: ClientProgramKey, progress: ClientDownloadProgress)
    {
        byKey[key] = progress
    }

    override fun clear(key: ClientProgramKey)
    {
        byKey.remove(key)
    }
}
