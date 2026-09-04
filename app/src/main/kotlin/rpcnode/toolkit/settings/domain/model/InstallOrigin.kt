package rpcnode.toolkit.settings.domain.model

import java.net.URI

/** CDN / panel origin agents fetch clients and the agent jar from. Never a trailing slash. */
@JvmInline
value class InstallOrigin private constructor(val value: String)
{
    fun channel(): Channel = Channel.from(this)

    companion object
    {
        const val LOCAL = "http://127.0.0.1:8094"
        const val PROD = "https://toolkit.rpcnode.dev"

        fun parse(raw: String): Parse
        {
            var u = raw.trim().trimEnd('/')
            if (u.endsWith("/install"))
            {
                u = u.removeSuffix("/install").trimEnd('/')
            }
            if (u == "http://127.0.0.1:8095" || u == "http://localhost:8095")
            {
                u = LOCAL
            }
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
            return Parse.Ok(InstallOrigin(u))
        }
    }

    sealed interface Parse
    {
        data class Ok(val origin: InstallOrigin) : Parse
        data object Empty : Parse
        data object Invalid : Parse
    }
}
