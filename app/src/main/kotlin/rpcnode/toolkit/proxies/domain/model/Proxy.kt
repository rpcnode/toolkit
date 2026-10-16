package rpcnode.toolkit.proxies.domain.model

import java.net.URI
import java.util.UUID

@JvmInline
value class ProxyId private constructor(val value: String)
{
    companion object
    {
        fun parse(raw: String): ProxyId? = raw.trim().ifEmpty { null }?.let(::ProxyId)

        fun generate(): ProxyId = ProxyId(UUID.randomUUID().toString())
    }
}

/**
 * `http://[user:pass@]host:port` — an HTTP (CONNECT) proxy. Only `http://`: Java HttpClient, curl, aria2 and the
 * chain tools (reqwest, Go net/http) all speak it; https/socks proxies are not understood by every one of them.
 */
@JvmInline
value class ProxyUrl private constructor(val value: String)
{
    /** [value] with the password replaced by `***` — safe for the API and logs. */
    val masked: String
        get()
        {
            val info = URI(value).rawUserInfo ?: return value
            val user = info.substringBefore(':')
            return value.replaceFirst("$info@", if (':' in info) "$user:***@" else "$user@")
        }

    sealed interface Parse
    {
        data class Ok(val url: ProxyUrl) : Parse
        data object Empty : Parse
        data class Invalid(val reason: String) : Parse
    }

    companion object
    {
        fun parse(raw: String): Parse
        {
            val t = raw.trim().trimEnd('/')
            if (t.isEmpty())
            {
                return Parse.Empty
            }
            val uri = runCatching { URI(t) }.getOrNull() ?: return Parse.Invalid("not a URL")
            if (!uri.scheme.equals("http", ignoreCase = true))
            {
                return Parse.Invalid("only http:// proxies are supported (http://user:pass@host:port)")
            }
            if (uri.host.isNullOrBlank())
            {
                return Parse.Invalid("host is required")
            }
            if (uri.port !in 1..65535)
            {
                return Parse.Invalid("port is required (http://host:port)")
            }
            if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null)
            {
                return Parse.Invalid("proxy URL must not have a path or query")
            }
            return Parse.Ok(ProxyUrl(t))
        }
    }
}

data class Proxy(
    val id: ProxyId,
    val name: String,
    val url: ProxyUrl,
)
