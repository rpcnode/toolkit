package rpcnode.toolkit.install.application

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Version + staged jar for `/install/version` and self-update. Host install: `sudo java -jar rpcnode-agent.jar install`. */
class RenderAgentScriptUseCase(
    private val installDir: Path,
    private val agentVersion: String = classpathAgentVersion(),
)
{
    private data class Cached(val size: Long, val modifiedMs: Long, val version: String)

    @Volatile
    private var cached: Cached? = null

    /**
     * Version of the agent jar this panel actually serves (`/install/binaries/rpcnode-agent.jar`),
     * read from the jar's own `agent/version`. Falls back to the panel's own `chainAgentVersion`
     * only when no jar is staged.
     *
     * The panel's own value is not enough: a stale jar left in `install/binaries` made the panel
     * announce a newer agent than it served, so hosts "updated" to the same old jar forever.
     */
    fun version(): String
    {
        val staged = jar()
        if (staged != null)
        {
            val fromJar = jarVersion(staged)
            if (!fromJar.isNullOrBlank())
            {
                return fromJar
            }
        }
        return agentVersion.trim()
    }

    fun jar(): Path?
    {
        val latest = installDir.resolve("binaries").resolve("rpcnode-agent.jar")
        return latest.takeIf { Files.isRegularFile(it) }
    }

    private fun jarVersion(jar: Path): String?
    {
        val size = runCatching { Files.size(jar) }.getOrNull() ?: return null
        val modified = runCatching { Files.getLastModifiedTime(jar).toMillis() }.getOrDefault(0L)
        cached?.let { if (it.size == size && it.modifiedMs == modified) return it.version }
        val version = runCatching {
            ZipFile(jar.toFile()).use { zip ->
                val entry = zip.getEntry("agent/version") ?: return@use null
                zip.getInputStream(entry).bufferedReader().use { it.readText().trim() }
            }
        }.getOrNull()
        if (!version.isNullOrBlank())
        {
            cached = Cached(size, modified, version)
        }
        return version
    }
}

fun classpathAgentVersion(): String =
    RenderAgentScriptUseCase::class.java.getResourceAsStream("/agent/version")
        ?.bufferedReader()
        ?.use { it.readText().trim() }
        .orEmpty()
