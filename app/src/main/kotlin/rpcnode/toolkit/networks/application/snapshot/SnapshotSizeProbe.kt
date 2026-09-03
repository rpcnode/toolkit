package rpcnode.toolkit.networks.application.snapshot

/**
 * Optional shared size read for archives that expose a single-file Content-Length.
 * Networks whose size comes from a manifest, README, or multi-part set implement that
 * themselves and do not call this.
 */
fun interface SnapshotSizeProbe
{
    suspend fun bytes(url: String): Long?
}
