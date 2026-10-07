package rpcnode.toolkit.networks.application.snapshot

import java.net.URI

/** Version id and filename extracted from an official snapshot archive URL. */
object SnapshotArchiveRef
{
    private val BACKUP_DIR = Regex("""backup\d{8}""")

    fun versionFromUrl(url: String): String?
    {
        if (isBaseManifestUrl(url))
        {
            return null
        }
        val parts = pathParts(url) ?: return null
        for (part in parts)
        {
            if (BACKUP_DIR.matches(part))
            {
                return part
            }
        }
        return parts.dropLast(1).lastOrNull()
    }

    fun filenameFromUrl(url: String): String?
    {
        if (isBaseManifestUrl(url))
        {
            return null
        }
        val parts = pathParts(url) ?: return null
        return parts.lastOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun isBaseManifestUrl(url: String): Boolean
    {
        val lower = url.trim().lowercase()
        return lower.startsWith("https://chain.base.org/api/snapshots") ||
            lower.startsWith("base-official://") ||
            lower.startsWith("formal-r2://") ||
            lower.startsWith("bsc-official://")
    }

    private fun pathParts(url: String): List<String>?
    {
        val path = try
        {
            URI(url).path
        }
        catch (_: Exception)
        {
            return null
        }
        if (path.isNullOrBlank())
        {
            return null
        }
        return path.split('/').filter { it.isNotEmpty() }
    }
}
