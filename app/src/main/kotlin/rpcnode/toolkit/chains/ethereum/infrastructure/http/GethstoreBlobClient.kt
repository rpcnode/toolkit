package rpcnode.toolkit.chains.ethereum.infrastructure.http

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Lists geth linux tarballs on Azure gethstore for a version (commit suffix in blob name).
 * Mirrors Go `tools/client-sync/gethstore.go`.
 */
class GethstoreBlobClient(
    private val origin: String = "https://gethstore.blob.core.windows.net",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    private val timeout: Duration = Duration.ofSeconds(30),
)
{
    fun resolveLinuxTarballUrl(version: String, aarch64: Boolean): String?
    {
        val ver = version.trim().removePrefix("v").removePrefix("V")
        if (ver.isEmpty())
        {
            return null
        }
        val arch = if (aarch64) "arm64" else "amd64"
        val prefix = "geth-linux-$arch-$ver-"
        val names = listBlobs(prefix) ?: return null
        val name = pickTarball(names, arch, ver) ?: return null
        return "${origin.trimEnd('/')}/builds/$name"
    }

    fun listBlobs(prefix: String): List<String>?
    {
        val out = mutableListOf<String>()
        var marker = ""
        repeat(20) {
            val url = listUrl(prefix, marker)
            val body = getText(url) ?: return null
            val (names, next) = parseList(body) ?: return null
            out += names
            if (next.isEmpty())
            {
                return out
            }
            marker = next
        }
        return out
    }

    fun pickTarball(names: List<String>, linuxArch: String, ver: String): String?
    {
        val prefix = "geth-linux-$linuxArch-$ver-"
        for (n in names)
        {
            val name = n.trim()
            if (!name.startsWith(prefix)) continue
            if (name.contains("unstable")) continue
            if (name.endsWith(".asc") || name.endsWith(".sig")) continue
            if (!name.endsWith(".tar.gz")) continue
            val rest = name.removePrefix(prefix).removeSuffix(".tar.gz")
            if (rest.length != 8) continue
            return name
        }
        return null
    }

    private fun listUrl(prefix: String, marker: String): String
    {
        val enc = { s: String -> URLEncoder.encode(s, StandardCharsets.UTF_8) }
        val base = "${origin.trimEnd('/')}/builds?restype=container&comp=list&prefix=${enc(prefix)}"
        return if (marker.isEmpty()) base else "$base&marker=${enc(marker)}"
    }

    private fun getText(url: String): String?
    {
        return try
        {
            val resp = http.send(
                HttpRequest.newBuilder(URI(url))
                    .timeout(timeout)
                    .header("User-Agent", "rpcnode-toolkit")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (resp.statusCode() in 200 until 300) resp.body() else null
        }
        catch (_: Exception)
        {
            null
        }
    }

    private fun parseList(body: String): Pair<List<String>, String>?
    {
        return try
        {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(body.byteInputStream())
            val names = mutableListOf<String>()
            val blobs = doc.getElementsByTagName("Blob")
            for (i in 0 until blobs.length)
            {
                val blob = blobs.item(i) as? Element ?: continue
                val nameNodes = blob.getElementsByTagName("Name")
                if (nameNodes.length > 0)
                {
                    val n = nameNodes.item(0).textContent?.trim().orEmpty()
                    if (n.isNotEmpty()) names += n
                }
            }
            val next = doc.getElementsByTagName("NextMarker").item(0)?.textContent?.trim().orEmpty()
            names to next
        }
        catch (_: Exception)
        {
            null
        }
    }
}
