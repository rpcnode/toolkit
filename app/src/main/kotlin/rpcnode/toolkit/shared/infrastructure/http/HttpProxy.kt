package rpcnode.toolkit.shared.infrastructure.http

import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.ProxySelector
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient

/** java.net.http through an `http://[user:pass@]host:port` proxy (Java ignores HTTPS_PROXY env). */
object HttpProxy
{
    /**
     * Basic auth on the CONNECT tunnel is disabled by the JDK by default; call once at process start, before the
     * first HttpClient is built.
     */
    fun allowTunnelBasicAuth()
    {
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")
    }

    /** Route [builder] through [proxy] (Basic auth from its user info); blank = direct. */
    fun apply(builder: HttpClient.Builder, proxy: String)
    {
        val uri = proxy.trim().takeIf { it.isNotEmpty() }?.let { runCatching { URI(it) }.getOrNull() } ?: return
        val host = uri.host ?: return
        val port = if (uri.port > 0) uri.port else 8080
        builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(host, port)))
        val info = uri.rawUserInfo ?: return
        val user = URLDecoder.decode(info.substringBefore(':'), Charsets.UTF_8)
        val pass = URLDecoder.decode(info.substringAfter(':', ""), Charsets.UTF_8)
        builder.authenticator(object : Authenticator()
        {
            override fun getPasswordAuthentication(): PasswordAuthentication? =
                if (requestorType == RequestorType.PROXY) PasswordAuthentication(user, pass.toCharArray()) else null
        })
    }
}
