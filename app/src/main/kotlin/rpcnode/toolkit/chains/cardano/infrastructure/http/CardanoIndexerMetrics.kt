package rpcnode.toolkit.chains.cardano.infrastructure.http

import rpcnode.toolkit.chains.cardano.infrastructure.CardanoCluster

/** Prometheus text → `name → value` (labels dropped). Shared by the Ogmios and db-sync scrapes. */
internal fun parsePrometheus(body: String?): Map<String, Double>
{
    if (body.isNullOrBlank())
    {
        return emptyMap()
    }
    val out = HashMap<String, Double>()
    for (line in body.lineSequence())
    {
        if (line.isEmpty() || line[0] == '#') continue
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) continue
        val value = parts[1].toDoubleOrNull() ?: continue
        out[parts[0].substringBefore('{')] = value
    }
    return out
}

/**
 * cardano-db-sync's own Prometheus (`PrometheusPort` of db-sync-config.json; metric names verified in
 * `Cardano.DbSync.Metrics` @ 13.7.2.1): the tip it has written to PostgreSQL and the tip of the node it follows.
 */
object CardanoDbSyncMetrics
{
    /** [nodeBlock] is the block height of the node as db-sync sees it; [queue] the DB events still to be written. */
    data class Reading(val dbBlock: Long, val dbSlot: Long, val nodeBlock: Long?, val queue: Long?, val epoch: Long?)

    /** db-sync counts as at the tip when the newest block in the database is this many seconds (1-second slots) behind the wall clock. */
    const val AT_TIP_SLOTS = 300L

    fun parse(body: String?): Reading?
    {
        val m = parsePrometheus(body)
        val block = m["cardano_db_sync_db_block_height"] ?: return null
        val slot = m["cardano_db_sync_db_slot_height"] ?: return null
        return Reading(
            dbBlock = block.toLong(),
            dbSlot = slot.toLong(),
            nodeBlock = m["cardano_db_sync_node_block_height"]?.toLong(),
            queue = m["cardano_db_sync_db_queue_length"]?.toLong(),
            epoch = m["cardano_db_sync_db_epoch_sync_number"]?.toLong(),
        )
    }

    /** Progress of the database against the wall clock, same slot clock as the node reading. */
    data class Progress(val pct: Double, val lagSlots: Long, val atTip: Boolean)

    fun progress(reading: Reading, cluster: CardanoCluster, nowUnixSeconds: Long): Progress
    {
        val wall = cluster.currentSlot(nowUnixSeconds)
        val lag = (wall - reading.dbSlot).coerceAtLeast(0)
        if (lag <= AT_TIP_SLOTS)
        {
            return Progress(100.0, lag, true)
        }
        val span = (wall - cluster.shelleyStartSlot).coerceAtLeast(1)
        val done = (reading.dbSlot - cluster.shelleyStartSlot).coerceAtLeast(0)
        return Progress((done.toDouble() / span * 100.0).coerceIn(0.0, 99.9), lag, false)
    }
}

/** Ogmios `/metrics` (names verified in `Ogmios.Data.Metrics.Prometheus` @ v7.0.0). */
object CardanoOgmiosMetrics
{
    /** [sync] = `ogmios_network_synchronization` (0..1, null while unknown); [tipBlock] absent at genesis / before the first tip. */
    data class Reading(val connected: Boolean, val sync: Double?, val tipBlock: Long?, val activeConnections: Long?)

    fun parse(body: String?): Reading?
    {
        val m = parsePrometheus(body)
        val connected = m["ogmios_connected"] ?: return null
        return Reading(
            connected = connected >= 1.0,
            sync = m["ogmios_network_synchronization"],
            tipBlock = m["ogmios_tip_block"]?.toLong(),
            activeConnections = m["ogmios_active_connections"]?.toLong(),
        )
    }

    /** Ogmios reports the share of the chain (by time) the node has reached; at or above this the node is at the tip. */
    const val AT_TIP = 0.999
}
