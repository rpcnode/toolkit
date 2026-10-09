package rpcnode.toolkit.networks.application.tip

import java.util.concurrent.ConcurrentHashMap
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository

/**
 * Public tip cache keyed by network/env.
 * TTL matches host height push (~30s): each height ingest refreshes tip at most once per interval.
 */
class NetworkTipCache(
    private val facts: NetworkFactsRepository,
    private val tipProbes: NetworkTipProbeRegistry,
    private val ttlMs: Long = 30_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
)
{
    private data class Entry(val tip: Long, val atMs: Long)

    private val byKey = ConcurrentHashMap<String, Entry>()

    suspend fun tip(network: NetworkId, env: EnvId): Long?
    {
        val key = "${network.value}/${env.value}"
        val now = nowMs()
        byKey[key]?.let { hit ->
            if (now - hit.atMs < ttlMs)
            {
                return hit.tip
            }
        }
        val urls = facts.factsFor(network)
            ?.envs
            ?.firstOrNull { it.id.equals(env.value, ignoreCase = true) }
            ?.publicTipUrls
            .orEmpty()
        if (urls.isEmpty())
        {
            return null
        }
        val tip = tipProbes.forNetwork(network)?.tip(urls) ?: return null
        if (tip < 0)
        {
            return null
        }
        byKey[key] = Entry(tip = tip, atMs = now)
        return tip
    }
}
