package rpcnode.toolkit.chains.etc.infrastructure.http

import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc
import rpcnode.toolkit.nodes.application.start.HostNodeHeightReading

/** Pure combination of `eth_blockNumber`, `eth_syncing` and the snap-sync log percentage into one reading. */
object EtcSyncReading
{
    fun combine(
        block: Long?,
        sync: EthereumEthRpc.SyncingStatus?,
        logPct: Double?,
    ): HostNodeHeightReading?
    {
        val height = listOfNotNull(block, sync?.currentBlock).maxOrNull() ?: return null
        if (sync == null)
        {
            return HostNodeHeightReading(height = height, syncPct = logPct, syncing = logPct != null && logPct < 100.0)
        }
        if (!sync.syncing)
        {
            // geth answers eth_syncing=false (and block 0) while the snap sync is still in its state download
            if (height == 0L && logPct != null && logPct < 100.0)
            {
                return HostNodeHeightReading(height = height, syncPct = logPct, syncing = true)
            }
            return HostNodeHeightReading(height = height, syncPct = 100.0, syncing = false)
        }
        val candidates = listOfNotNull(sync.blockPct, logPct)
        val pct = if (candidates.isEmpty()) null else candidates.min().coerceIn(0.0, 99.9)
        return HostNodeHeightReading(height = height, syncPct = pct, syncing = true)
    }
}
