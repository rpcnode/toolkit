package rpcnode.toolkit.agent.infrastructure.node

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** First non-empty line of `{nodeDir}/VERSION` — host source of truth for client version hooks. */
fun readNodeClientVersion(nodeDir: String): String
{
    val dir = nodeDir.trim()
    if (dir.isEmpty() || !dir.startsWith("/") || ".." in dir)
    {
        return ""
    }
    val file = Path.of(dir, "VERSION")
    if (!Files.isRegularFile(file))
    {
        return ""
    }
    return runCatching {
        Files.readString(file).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }.getOrDefault("")
}

/**
 * Host client version for panel hooks: prefer on-disk VERSION.
 * If missing and [seed] is non-blank, write VERSION once so later height pushes keep reporting it.
 */
fun resolveHostClientVersion(nodeDir: String, seed: String = ""): String
{
    val fromDisk = readNodeClientVersion(nodeDir)
    if (fromDisk.isNotEmpty())
    {
        return fromDisk
    }
    val fromSeed = seed.trim()
    if (fromSeed.isEmpty())
    {
        return ""
    }
    writeNodeClientVersion(nodeDir, fromSeed)
    return fromSeed
}

fun writeNodeClientVersion(nodeDir: String, version: String): Boolean
{
    val dir = nodeDir.trim()
    val ver = version.trim()
    if (dir.isEmpty() || !dir.startsWith("/") || ".." in dir || ver.isEmpty())
    {
        return false
    }
    return try
    {
        val root = Path.of(dir)
        Files.createDirectories(root)
        Files.writeString(root.resolve("VERSION"), "$ver\n")
        true
    }
    catch (_: Exception)
    {
        false
    }
}

/** Best-effort pull of `/install/clients/{network}/{env}/VERSION` from the panel (same as client sync). */
fun downloadNodeClientVersionFromPanel(
    panelUrl: String,
    network: String,
    env: String,
    nodeDir: String,
    http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    timeout: Duration = Duration.ofSeconds(30),
): Boolean
{
    val base = panelUrl.trim().trimEnd('/')
    val net = network.trim().lowercase()
    val envId = env.trim().lowercase()
    val dir = nodeDir.trim()
    if (base.isEmpty() || net.isEmpty() || envId.isEmpty() || dir.isEmpty() || !dir.startsWith("/") || ".." in dir)
    {
        return false
    }
    if (!readNodeClientVersion(dir).isEmpty())
    {
        return true
    }
    val url = "$base/install/clients/$net/$envId/VERSION"
    val bytes = try
    {
        val resp = http.send(
            HttpRequest.newBuilder(URI(url)).timeout(timeout).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        if (resp.statusCode() in 200 until 300) resp.body() else null
    }
    catch (_: Exception)
    {
        null
    } ?: return false
    if (bytes.isEmpty())
    {
        return false
    }
    return try
    {
        val root = Path.of(dir)
        Files.createDirectories(root)
        Files.write(root.resolve("VERSION"), bytes)
        true
    }
    catch (_: Exception)
    {
        false
    }
}
