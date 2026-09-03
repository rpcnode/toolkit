package rpcnode.toolkit.agent.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Total bytes under a node data directory (`du -sb`), cached to avoid walking
 * multi-TB trees on every height push.
 */
class NodeDirSizeProbe(
    private val ttlMs: Long = 90_000L,
    private val timeoutSec: Long = 120L,
)
{
    private data class Sample(val bytes: Long, val atMs: Long)

    private val cache = ConcurrentHashMap<String, Sample>()

    /** Bytes under [nodeDir], or -1 when unreadable / failed. */
    fun sizeBytes(nodeDir: String): Long
    {
        val dir = nodeDir.trim()
        if (dir.isEmpty() || !dir.startsWith("/") || dir.contains(".."))
        {
            return -1
        }
        val now = System.currentTimeMillis()
        val hit = cache[dir]
        if (hit != null && now - hit.atMs < ttlMs)
        {
            return hit.bytes
        }
        val measured = measure(dir)
        if (measured >= 0)
        {
            cache[dir] = Sample(measured, now)
        }
        return measured
    }

    private fun measure(dir: String): Long
    {
        val path = Path.of(dir)
        if (!Files.isDirectory(path))
        {
            return -1
        }
        val fromDu = duSb(dir)
        if (fromDu >= 0)
        {
            return fromDu
        }
        return walkSum(path)
    }

    private fun duSb(dir: String): Long
    {
        return try
        {
            val proc = ProcessBuilder("du", "-sb", dir)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished)
            {
                proc.destroyForcibly()
                return -1
            }
            if (proc.exitValue() != 0)
            {
                return -1
            }
            val line = proc.inputStream.bufferedReader().use { it.readLine() }?.trim().orEmpty()
            val token = line.substringBefore('\t').substringBefore(' ').trim()
            token.toLongOrNull()?.takeIf { it >= 0 } ?: -1
        }
        catch (_: Exception)
        {
            -1
        }
    }

    private fun walkSum(root: Path): Long
    {
        return try
        {
            var sum = 0L
            Files.walk(root).use { stream ->
                stream.forEach { p ->
                    if (Files.isRegularFile(p))
                    {
                        sum += Files.size(p).coerceAtLeast(0)
                    }
                }
            }
            sum
        }
        catch (_: Exception)
        {
            -1
        }
    }
}
