package rpcnode.toolkit.networks.domain.model

/**
 * One live snapshot archive for a single env. How the URL and [sizeBytes] are found is
 * per-network — a dated HTML listing, a JSON index, a README scrape, a HEAD, … The shared
 * listing helper is only for mirrors that happen to look the same.
 */
data class SnapshotArchive(
    val url: String,
    /** true when this archive can be streamed into the extractor (download + unpack in one pass). */
    val streamUnpack: Boolean,
    /** Live size in bytes when this network can determine it; null if unknown right now. */
    val sizeBytes: Long? = null,
)
