package rpcnode.toolkit.chains.cardano.infrastructure.http

import rpcnode.toolkit.chains.cardano.infrastructure.CardanoCluster
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

/** cardano-node Prometheus (`PrometheusSimple`) scrape: block number and slot number of the chain tip. */
object CardanoMetrics
{
    data class Tip(val block: Long, val slot: Long?)

    /** A node whose tip slot is within this many seconds of the wall clock counts as caught up. */
    const val CAUGHT_UP_SLOTS = 120L

    private val blockNames = setOf("cardano_node_metrics_blockNum_int", "cardano_node_metrics_blockNum")
    private val slotNames = setOf("cardano_node_metrics_slotNum_int", "cardano_node_metrics_slotNum")

    fun parse(body: String?): Tip?
    {
        if (body.isNullOrBlank())
        {
            return null
        }
        var block: Long? = null
        var slot: Long? = null
        for (line in body.lineSequence())
        {
            if (line.isEmpty() || line[0] == '#') continue
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val name = parts[0].substringBefore('{')
            val value = parts[1].toDoubleOrNull()?.toLong() ?: continue
            when (name)
            {
                in blockNames -> block = value
                in slotNames -> slot = value
            }
        }
        return block?.let { Tip(it, slot) }
    }

    suspend fun scrape(http: SimpleHttp, metricsPort: Int): Tip?
    {
        if (metricsPort <= 0)
        {
            return null
        }
        return parse(http.getText("http://127.0.0.1:$metricsPort/metrics"))
    }

    /**
     * Sync % = how far the tip slot has come between the start of the Shelley 1-second slot clock and now.
     * Below [CAUGHT_UP_SLOTS] from the wall clock the node is synced; without a slot sample the % is unknown.
     */
    fun reading(tip: Tip, cluster: CardanoCluster, nowUnixSeconds: Long): HostNodeHeightReading
    {
        val slot = tip.slot ?: return HostNodeHeightReading(height = tip.block, syncPct = null, syncing = false)
        val wall = cluster.currentSlot(nowUnixSeconds)
        val lag = wall - slot
        if (lag <= CAUGHT_UP_SLOTS)
        {
            return HostNodeHeightReading(height = tip.block, syncPct = 100.0, syncing = false)
        }
        val span = (wall - cluster.shelleyStartSlot).coerceAtLeast(1)
        val done = (slot - cluster.shelleyStartSlot).coerceAtLeast(0)
        val pct = (done.toDouble() / span * 100.0).coerceIn(0.0, 99.9)
        return HostNodeHeightReading(height = tip.block, syncPct = pct, syncing = true)
    }
}
