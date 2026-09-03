package rpcnode.toolkit.agent.application.client

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Progress for the host client-update job (polled by the panel / modal). */
data class ClientUpdateSnapshot(
    val nodeId: String = "",
    val network: String = "",
    val env: String = "",
    val nodeDir: String = "",
    val local: String = "",
    val latest: String = "",
    val previousVersion: String = "",
    val updateAvailable: Boolean = false,
    val phase: String = "idle",
    val step: String = "",
    val detail: String = "",
    val pct: Int = 0,
    val lastError: String = "",
    val logTail: String = "",
)

/**
 * In-memory client-update progress keyed by node id (one job per node).
 * Survives only for the agent process lifetime.
 */
class ClientUpdateStateStore
{
    private val byNode = ConcurrentHashMap<String, AtomicReference<ClientUpdateSnapshot>>()

    fun get(nodeId: String): ClientUpdateSnapshot
    {
        val id = nodeId.trim()
        if (id.isEmpty())
        {
            return ClientUpdateSnapshot()
        }
        return byNode[id]?.get() ?: ClientUpdateSnapshot(nodeId = id)
    }

    /** Prefer an in-flight / last error job; else first matching network+env. */
    fun find(nodeId: String = "", network: String = "", env: String = ""): ClientUpdateSnapshot
    {
        val id = nodeId.trim()
        if (id.isNotEmpty())
        {
            return get(id)
        }
        val net = network.trim().lowercase()
        val envId = env.trim().lowercase()
        if (net.isEmpty() || envId.isEmpty())
        {
            return ClientUpdateSnapshot()
        }
        return byNode.values
            .map { it.get() }
            .firstOrNull { it.network == net && it.env == envId }
            ?: ClientUpdateSnapshot(network = net, env = envId)
    }

    fun update(nodeId: String, transform: (ClientUpdateSnapshot) -> ClientUpdateSnapshot): ClientUpdateSnapshot
    {
        val id = nodeId.trim()
        if (id.isEmpty())
        {
            return ClientUpdateSnapshot()
        }
        val ref = byNode.computeIfAbsent(id) {
            AtomicReference(ClientUpdateSnapshot(nodeId = id))
        }
        while (true)
        {
            val cur = ref.get()
            val next = transform(cur).copy(nodeId = id)
            if (ref.compareAndSet(cur, next))
            {
                return next
            }
        }
    }

    fun set(snapshot: ClientUpdateSnapshot): ClientUpdateSnapshot
    {
        val id = snapshot.nodeId.trim()
        if (id.isEmpty())
        {
            return snapshot
        }
        byNode[id] = AtomicReference(snapshot.copy(nodeId = id))
        return snapshot
    }
}
