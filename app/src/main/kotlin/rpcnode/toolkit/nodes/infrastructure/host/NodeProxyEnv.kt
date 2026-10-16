package rpcnode.toolkit.nodes.infrastructure.host

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Per-node outbound HTTP proxy on the host: `<node_dir>/.toolkit/proxy.env`.
 * Every node unit references it with an optional `EnvironmentFile=-…` line, so setting or clearing the proxy on a
 * running node is a file write plus a restart. Snapshot downloads read the same file.
 */
object NodeProxyEnv
{
    const val FILE = ".toolkit/proxy.env"

    /** Local traffic (RPC probes, IPC-over-HTTP helpers, the node's own ports) never goes through the proxy. */
    const val NO_PROXY = "localhost,127.0.0.1,::1"

    fun path(nodeDir: Path): Path = nodeDir.resolve(FILE).toAbsolutePath()

    /** Proxy variables for a child process — upper and lower case, curl reads only `http_proxy` in lower case. */
    fun vars(url: String?): Map<String, String>
    {
        val u = url?.trim().orEmpty()
        if (u.isEmpty())
        {
            return emptyMap()
        }
        val out = linkedMapOf<String, String>()
        for (key in listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY"))
        {
            out[key] = u
            out[key.lowercase()] = u
        }
        out["NO_PROXY"] = NO_PROXY
        out["no_proxy"] = NO_PROXY
        return out
    }

    /** Write (or remove, when [url] is blank) the node's proxy file. */
    fun write(nodeDir: Path, url: String?)
    {
        val file = path(nodeDir)
        val vars = vars(url)
        if (vars.isEmpty())
        {
            Files.deleteIfExists(file)
            return
        }
        Files.createDirectories(file.parent)
        Files.writeString(file, vars.entries.joinToString("") { (k, v) -> "$k=${quote(v)}\n" })
        runCatching {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }
    }

    /** Proxy URL currently configured for the node, or null. */
    fun read(nodeDir: Path): String?
    {
        val file = path(nodeDir)
        if (!Files.isRegularFile(file))
        {
            return null
        }
        return runCatching {
            Files.readAllLines(file)
                .firstOrNull { it.startsWith("HTTPS_PROXY=") }
                ?.substringAfter('=')
                ?.let(::unquote)
                ?.trim()
                ?.ifEmpty { null }
        }.getOrNull()
    }

    /** [url] with the password replaced by `***`, for logs and the panel. */
    fun mask(url: String?): String
    {
        val u = url?.trim().orEmpty()
        val uri = runCatching { URI(u) }.getOrNull() ?: return u
        val info = uri.rawUserInfo ?: return u
        val user = info.substringBefore(':')
        return u.replaceFirst("$info@", if (':' in info) "$user:***@" else "$user@")
    }

    /** Add `EnvironmentFile=-<node_dir>/.toolkit/proxy.env` right after `[Service]` (idempotent). */
    fun withEnvironmentFile(unitBody: String, nodeDir: Path): String
    {
        val line = "EnvironmentFile=-${path(nodeDir)}"
        if (unitBody.lineSequence().any { it.trim() == line })
        {
            return unitBody
        }
        val service = Regex("(?m)^\\[Service\\]\\s*$")
        val match = service.find(unitBody) ?: return unitBody
        val insertAt = match.range.last + 1
        return unitBody.substring(0, insertAt) + "\n$line" + unitBody.substring(insertAt)
    }

    private fun quote(v: String): String = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun unquote(v: String): String
    {
        val t = v.trim()
        if (t.length >= 2 && t.startsWith('"') && t.endsWith('"'))
        {
            return t.substring(1, t.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return t
    }
}
