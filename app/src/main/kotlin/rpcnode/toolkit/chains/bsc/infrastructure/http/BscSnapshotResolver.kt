package rpcnode.toolkit.chains.bsc.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.bsc.infrastructure.BscClusters
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.domain.model.SnapshotArchive

/**
 * Official BSC snapshots are multi-part (`fetch-snapshot.sh`), not a single CDN tarball.
 * Returns a `bsc-official://` sentinel URL; the host agent runs
 * [BscOfficialSnapshotRunner].
 */
class BscSnapshotResolver : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        val cluster = BscClusters.lookup(env.value)
        if (cluster.env != "mainnet" && cluster.env != "testnet")
        {
            return null
        }
        val flavor = BscClusters.normalizeSnapshotFlavor(typeId.ifBlank { "pruned" })
        return SnapshotArchive(
            url = "$SCHEME://${cluster.env}/$flavor",
            streamUnpack = false,
            sizeBytes = null,
        )
    }

    companion object
    {
        const val SCHEME = "bsc-official"

        fun isOfficialUrl(url: String): Boolean =
            url.trim().lowercase().startsWith("$SCHEME://")

        fun parse(url: String): OfficialRef?
        {
            val raw = url.trim()
            if (!isOfficialUrl(raw))
            {
                return null
            }
            val rest = raw.substringAfter("://").substringBefore('?').trim('/')
            val parts = rest.split('/').filter { it.isNotBlank() }
            if (parts.size < 2)
            {
                return null
            }
            val cluster = BscClusters.lookup(parts[0])
            val flavor = BscClusters.normalizeSnapshotFlavor(parts[1])
            val snapRaw = raw.substringAfter("?snap=", "").substringBefore('&').trim()
                .takeIf { it.isNotBlank() }
            val snap = snapRaw?.let { decodeSnapDir(it) }?.takeIf { it.isNotBlank() }
            return OfficialRef(env = cluster.env, flavor = flavor, snapDir = snap)
        }

        fun withSnapDir(url: String, snapDir: String?): String
        {
            val base = url.trim().substringBefore('?')
            val snap = snapDir?.trim().orEmpty()
            if (snap.isEmpty())
            {
                return base
            }
            return "$base?snap=${java.net.URLEncoder.encode(snap, Charsets.UTF_8)}"
        }

        /** Undoes [withSnapDir] encoding so absolute paths stay absolute (`/` ≠ `%2F`). */
        private fun decodeSnapDir(raw: String): String =
            runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8) }.getOrDefault(raw).trim()
    }

    data class OfficialRef(
        val env: String,
        val flavor: String,
        val snapDir: String?,
    )
}
