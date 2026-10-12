package rpcnode.toolkit.nodes.application.snapshot

import java.net.URI

/**
 * Operator-supplied snapshot archive (a direct link instead of the official / CDN mirror). The host agent downloads it,
 * so any reachable http(s) URL to a `.tgz` / `.tar.gz` archive works.
 */
object CustomSnapshotUrl
{
    const val SOURCE_ID = "custom"

    fun isCustom(source: String?): Boolean = source?.trim()?.lowercase() == SOURCE_ID

    /** Trimmed URL, or null when it is not an absolute http(s) URL with a host. */
    fun parse(raw: String?): String?
    {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty() || url.any { it.isWhitespace() })
        {
            return null
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https")
        {
            return null
        }
        return url.takeIf { !uri.host.isNullOrBlank() }
    }
}
