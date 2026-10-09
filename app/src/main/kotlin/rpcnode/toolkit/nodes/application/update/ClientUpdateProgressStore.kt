package rpcnode.toolkit.nodes.application.update

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** One milestone the host reported during client update (shown in the admin modal). */
data class ClientUpdateEvent(
    val id: String,
    val label: String,
    val detail: String = "",
    val at: String = "",
)

/**
 * Panel-side cache of client-update progress pushed by the host agent
 * (`POST /api/agent/v1/nodes/client-update`).
 */
class ClientUpdateProgressStore
{
    private val byNode = ConcurrentHashMap<String, AtomicReference<ClientUpdateInfo>>()

    fun get(nodeId: String): ClientUpdateInfo?
    {
        val id = nodeId.trim()
        if (id.isEmpty())
        {
            return null
        }
        return byNode[id]?.get()
    }

    fun put(nodeId: String, info: ClientUpdateInfo): ClientUpdateInfo
    {
        val id = nodeId.trim()
        if (id.isEmpty())
        {
            return info
        }
        val ref = byNode.computeIfAbsent(id) { AtomicReference(ClientUpdateInfo()) }
        while (true)
        {
            val cur = ref.get()
            val merged = merge(cur, info)
            if (ref.compareAndSet(cur, merged))
            {
                return merged
            }
        }
    }

    private fun merge(cur: ClientUpdateInfo, incoming: ClientUpdateInfo): ClientUpdateInfo
    {
        val events = mergeEvents(cur.events, incoming.events)
        return ClientUpdateInfo(
            local = incoming.local.ifEmpty { cur.local },
            latest = incoming.latest.ifEmpty { cur.latest },
            previousVersion = incoming.previousVersion.ifEmpty { cur.previousVersion },
            updateAvailable = incoming.updateAvailable || cur.updateAvailable,
            phase = incoming.phase.ifEmpty { cur.phase },
            step = incoming.step.ifEmpty { cur.step },
            detail = incoming.detail.ifEmpty { cur.detail },
            pct = if (incoming.pct > 0) incoming.pct else cur.pct,
            lastError = incoming.lastError.ifEmpty { cur.lastError },
            logTail = incoming.logTail.ifEmpty { cur.logTail },
            events = events,
        )
    }

    private fun mergeEvents(
        cur: List<ClientUpdateEvent>,
        incoming: List<ClientUpdateEvent>,
    ): List<ClientUpdateEvent>
    {
        if (incoming.isEmpty())
        {
            return cur
        }
        val out = cur.toMutableList()
        for (ev in incoming)
        {
            val id = ev.id.trim().lowercase()
            if (id.isEmpty())
            {
                continue
            }
            val idx = out.indexOfFirst { it.id.equals(id, ignoreCase = true) }
            if (idx >= 0)
            {
                out[idx] = ev
            }
            else
            {
                out += ev
            }
        }
        return out
    }
}
