package rpcnode.toolkit.settings.domain.model

import java.net.URI

/**
 * Public origin for the Snapshot CDN (site + `/snapshots/`). Never a trailing slash.
 * Empty means CDN prefer is off. Unlike [InstallOrigin], does not remap `:8095` or strip `/install`.
 */
@JvmInline
value class SnapshotCdnOrigin private constructor(val value: String)
{
    companion object
    {
        fun parse(raw: String): Parse
        {
            val u = raw.trim().trimEnd('/')
            if (u.isEmpty())
            {
                return Parse.Empty
            }
            val uri = try
            {
                URI(u)
            }
            catch (_: Exception)
            {
                return Parse.Invalid
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https")
            {
                return Parse.Invalid
            }
            if (uri.host.isNullOrBlank())
            {
                return Parse.Invalid
            }
            return Parse.Ok(SnapshotCdnOrigin(u))
        }
    }

    sealed interface Parse
    {
        data class Ok(val origin: SnapshotCdnOrigin) : Parse
        data object Empty : Parse
        data object Invalid : Parse
    }
}
