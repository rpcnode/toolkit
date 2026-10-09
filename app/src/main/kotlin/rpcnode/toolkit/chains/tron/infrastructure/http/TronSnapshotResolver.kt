package rpcnode.toolkit.chains.tron.infrastructure.http

import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.application.snapshot.SnapshotSizeProbe
import rpcnode.toolkit.networks.domain.model.SnapshotArchive
import rpcnode.toolkit.networks.domain.model.SnapshotMirrorSpec
import rpcnode.toolkit.networks.domain.repository.SnapshotMirrorCatalog
import rpcnode.toolkit.networks.infrastructure.http.DirectoryListingResolver
import rpcnode.toolkit.networks.infrastructure.http.HttpDirectoryListingResolver
import rpcnode.toolkit.networks.infrastructure.http.HttpSnapshotPresenceProbe
import rpcnode.toolkit.networks.infrastructure.http.HttpSnapshotSizeProbe
import rpcnode.toolkit.networks.infrastructure.http.SnapshotPresenceProbe

/**
 * TRON snapshot archives from [SnapshotMirrorCatalog] (`clients/tron.yml` → snapshots).
 *
 * - `discover: listing` — Apache/nginx autoindex under the mirror root.
 * - `discover: dated` — HEAD `backupYYYYMMDD/<filename>` (Nile; root listing is denied).
 */
class TronSnapshotResolver(
    private val mirrors: SnapshotMirrorCatalog,
    private val listingResolver: DirectoryListingResolver = HttpDirectoryListingResolver(),
    private val sizeProbe: SnapshotSizeProbe = HttpSnapshotSizeProbe(),
    private val presence: SnapshotPresenceProbe = HttpSnapshotPresenceProbe(),
    private val clock: Clock = Clock.systemUTC(),
) : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        val spec = resolveSpec(env, typeId) ?: return null
        val url = when (spec.discover)
        {
            "dated" -> latestDatedArchiveUrl(spec)
            else ->
            {
                val root = spec.mirror.trim().let { if (it.endsWith("/")) it else "$it/" }
                listingResolver.latestArchiveUrl(root, BACKUP_DIR_PATTERN, spec.filename)
            }
        } ?: return null
        return SnapshotArchive(
            url = url,
            streamUnpack = true,
            sizeBytes = sizeProbe.bytes(url),
        )
    }

    private fun resolveSpec(env: EnvId, typeId: String): SnapshotMirrorSpec?
    {
        val wanted = typeId.trim().lowercase()
        if (wanted.isNotEmpty())
        {
            return mirrors.mirror(NetworkId.TRON, env, wanted)
        }
        return mirrors.typesFor(NetworkId.TRON, env).firstOrNull()
    }

    private suspend fun latestDatedArchiveUrl(spec: SnapshotMirrorSpec): String?
    {
        val root = spec.mirror.trim().trimEnd('/')
        val today = LocalDate.now(clock)
        for (offset in 0 until DATED_LOOKBACK_DAYS)
        {
            val stamp = today.minusDays(offset.toLong()).format(DAY_STAMP)
            val url = "$root/backup$stamp/${spec.filename}"
            if (presence.present(url))
            {
                return url
            }
        }
        return null
    }

    companion object
    {
        private const val DATED_LOOKBACK_DAYS = 60
        private val BACKUP_DIR_PATTERN = Regex("backup[0-9]{8}")
        private val DAY_STAMP = DateTimeFormatter.BASIC_ISO_DATE
    }
}
