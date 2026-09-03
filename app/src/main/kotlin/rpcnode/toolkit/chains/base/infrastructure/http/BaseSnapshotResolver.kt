package rpcnode.toolkit.chains.base.infrastructure.http

import java.net.URI
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.base.infrastructure.BaseClusters
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.domain.model.SnapshotArchive

/**
 * Official Base V2 snapshots are downloaded by `base-reth-node download`, not toolkit aria2.
 * Resolves to the public manifest API URL (shown in admin) with `env` / `flavor` query params;
 * the host agent still runs [BaseOfficialSnapshotRunner].
 *
 * Snapshot CDN mirrors publish a rewritten `…/manifest.json`; [isCdnManifestUrl] / [parse]
 * route those to the same runner with `--manifest-url`.
 */
class BaseSnapshotResolver : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        val cluster = BaseClusters.lookup(env.value)
        if (cluster.env != "mainnet" && cluster.env != "sepolia")
        {
            return null
        }
        val flavor = BaseClusters.normalizeSnapshotFlavor(typeId.ifBlank { "archive" })
        return SnapshotArchive(
            url = publicUrl(cluster.env, flavor),
            streamUnpack = false,
            sizeBytes = null,
        )
    }

    companion object
    {
        /** Legacy sentinel — still accepted by [isOfficialUrl] / [parse]. */
        const val SCHEME = "base-official"

        fun publicUrl(env: String, flavor: String): String
        {
            val e = BaseClusters.lookup(env).env
            val f = BaseClusters.normalizeSnapshotFlavor(flavor)
            return "${BaseClusters.SNAPSHOT_API_URL}?env=$e&flavor=$f"
        }

        fun isOfficialUrl(url: String): Boolean
        {
            val raw = url.trim()
            if (raw.isEmpty())
            {
                return false
            }
            val lower = raw.lowercase()
            if (lower.startsWith("$SCHEME://"))
            {
                return true
            }
            return lower.startsWith(BaseClusters.SNAPSHOT_API_URL.lowercase())
        }

        /** CDN-published Base V2 manifest (`/snapshots/base/.../manifest.json`). */
        fun isCdnManifestUrl(url: String): Boolean
        {
            val raw = url.trim()
            if (raw.isEmpty())
            {
                return false
            }
            val path = try
            {
                URI(raw).path?.lowercase().orEmpty()
            }
            catch (_: Exception)
            {
                return false
            }
            return path.contains("/snapshots/base/") && path.endsWith("/manifest.json")
        }

        fun isBaseDownloadUrl(url: String): Boolean =
            isOfficialUrl(url) || isCdnManifestUrl(url)

        fun parse(url: String): OfficialRef?
        {
            val raw = url.trim()
            if (isOfficialUrl(raw))
            {
                return parseOfficial(raw)
            }
            if (isCdnManifestUrl(raw))
            {
                return parseCdn(raw)
            }
            return null
        }

        /** Absolute manifest URL for `--manifest-url` (strips query). Null for official discovery. */
        fun manifestUrlForDownload(url: String): String?
        {
            if (!isCdnManifestUrl(url))
            {
                return null
            }
            return try
            {
                val uri = URI(url.trim())
                URI(uri.scheme, uri.authority, uri.path, null, null).toString()
            }
            catch (_: Exception)
            {
                url.trim().substringBefore('?')
            }
        }

        private fun parseOfficial(raw: String): OfficialRef?
        {
            if (raw.lowercase().startsWith("$SCHEME://"))
            {
                val rest = raw.substringAfter("://").substringBefore('?').trim('/')
                val parts = rest.split('/').filter { it.isNotBlank() }
                if (parts.size < 2)
                {
                    return null
                }
                val cluster = BaseClusters.lookup(parts[0])
                val flavor = BaseClusters.normalizeSnapshotFlavor(parts[1])
                return OfficialRef(env = cluster.env, flavor = flavor)
            }
            return try
            {
                val uri = URI(raw)
                val q = queryMap(uri)
                val env = BaseClusters.lookup(q["env"].orEmpty()).env
                val flavor = BaseClusters.normalizeSnapshotFlavor(q["flavor"])
                OfficialRef(env = env, flavor = flavor)
            }
            catch (_: Exception)
            {
                null
            }
        }

        private fun parseCdn(raw: String): OfficialRef?
        {
            return try
            {
                val uri = URI(raw)
                val q = queryMap(uri)
                val parts = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
                // …/snapshots/base/{env}/archive/{version}/manifest.json
                val baseIdx = parts.indexOfFirst { it.equals("base", ignoreCase = true) }
                val envFromPath = parts.getOrNull(baseIdx + 1).orEmpty()
                val env = BaseClusters.lookup(q["env"].orEmpty().ifBlank { envFromPath }).env
                val flavor = BaseClusters.normalizeSnapshotFlavor(q["flavor"].orEmpty().ifBlank { "archive" })
                OfficialRef(env = env, flavor = flavor)
            }
            catch (_: Exception)
            {
                null
            }
        }

        private fun queryMap(uri: URI): Map<String, String> =
            uri.rawQuery.orEmpty()
                .split('&')
                .mapNotNull { part ->
                    val i = part.indexOf('=')
                    if (i <= 0) null
                    else part.substring(0, i) to part.substring(i + 1)
                }
                .toMap()
    }

    data class OfficialRef(
        val env: String,
        val flavor: String,
    )
}
