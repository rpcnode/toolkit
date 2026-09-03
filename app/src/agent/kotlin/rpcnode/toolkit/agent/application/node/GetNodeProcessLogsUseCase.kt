package rpcnode.toolkit.agent.application.node

import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchRecord

data class NodeProcessLogsView(
    val nodeId: String,
    val path: String,
    val lines: List<String>,
    val truncated: Boolean,
)

sealed interface GetNodeProcessLogsResult
{
    data class Ok(val view: NodeProcessLogsView) : GetNodeProcessLogsResult
    data object NotFound : GetNodeProcessLogsResult
    data class NoLogYet(val expectedPath: String) : GetNodeProcessLogsResult
}

/**
 * Tail of the chain process log. Preferred path comes from panel/catalog
 * (`requirements.logFile`) or `{nodeDir}/.toolkit/launch.json`; fallback is
 * systemd capture `logs/node.out`.
 */
class GetNodeProcessLogsUseCase(
    private val registry: RunningNodeRegistry,
    private val defaultLines: Int = 200,
    private val maxLines: Int = 2000,
)
{
    private val json = Json { ignoreUnknownKeys = true }

    operator fun invoke(
        nodeIdRaw: String,
        linesRaw: Int?,
        nodeDirRaw: String? = null,
        logFileRaw: String? = null,
    ): GetNodeProcessLogsResult
    {
        val nodeId = nodeIdRaw.trim()
        if (nodeId.isEmpty())
        {
            return GetNodeProcessLogsResult.NotFound
        }
        val registered = registry.get(nodeId)
        val nodeDir = sanitizeNodeDir(nodeDirRaw) ?: sanitizeNodeDir(registered?.nodeDir)
            ?: return GetNodeProcessLogsResult.NotFound
        val relative = sanitizeRelativeLog(
            logFileRaw
                ?: registered?.logFile
                ?: readLaunchLogFile(Path.of(nodeDir)),
        ) ?: "logs/node.out"
        val logPath = Path.of(nodeDir).resolve(relative).normalize()
        if (!logPath.startsWith(Path.of(nodeDir).normalize()))
        {
            return GetNodeProcessLogsResult.NotFound
        }
        if (!Files.isRegularFile(logPath))
        {
            return GetNodeProcessLogsResult.NoLogYet(logPath.toAbsolutePath().toString())
        }
        val want = (linesRaw ?: defaultLines).coerceIn(1, maxLines)
        val (tail, truncated) = readTailLines(logPath, want)
        return GetNodeProcessLogsResult.Ok(
            NodeProcessLogsView(
                nodeId = nodeId,
                path = logPath.toAbsolutePath().toString(),
                lines = tail,
                truncated = truncated,
            ),
        )
    }

    private fun readLaunchLogFile(nodeDir: Path): String?
    {
        val file = nodeDir.resolve(".toolkit/launch.json")
        if (!Files.isRegularFile(file))
        {
            return null
        }
        return runCatching {
            json.decodeFromString<HostNodeLaunchRecord>(Files.readString(file)).logFile
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun sanitizeNodeDir(raw: String?): String?
    {
        val dir = raw?.trim().orEmpty()
        if (dir.isEmpty() || !dir.startsWith("/") || ".." in dir)
        {
            return null
        }
        return dir
    }

    private fun sanitizeRelativeLog(raw: String?): String?
    {
        val rel = raw?.trim().orEmpty()
        if (rel.isEmpty() || rel.startsWith("/") || ".." in rel.split('/', '\\'))
        {
            return null
        }
        return rel
    }
}

/** Read up to [maxLines] lines from the end of [path] without loading the whole file. */
fun readTailLines(path: Path, maxLines: Int, maxBytes: Long = 512_000L): Pair<List<String>, Boolean>
{
    return try
    {
        RandomAccessFile(path.toFile(), "r").use { raf ->
            val length = raf.length()
            if (length <= 0L)
            {
                return emptyList<String>() to false
            }
            val start = (length - maxBytes).coerceAtLeast(0L)
            val truncatedByBytes = start > 0L
            raf.seek(start)
            val buf = ByteArray((length - start).toInt())
            raf.readFully(buf)
            var text = String(buf, StandardCharsets.UTF_8)
            if (truncatedByBytes)
            {
                val nl = text.indexOf('\n')
                if (nl >= 0 && nl + 1 < text.length)
                {
                    text = text.substring(nl + 1)
                }
            }
            val all = text.split('\n').map { it.trimEnd('\r') }
            val cleaned = if (all.isNotEmpty() && all.last().isEmpty()) all.dropLast(1) else all
            val truncated = truncatedByBytes || cleaned.size > maxLines
            val tail = if (cleaned.size > maxLines) cleaned.takeLast(maxLines) else cleaned
            tail to truncated
        }
    }
    catch (_: Exception)
    {
        emptyList<String>() to false
    }
}
