package rpcnode.toolkit.chains.arc.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.chains.arc.infrastructure.ArcClusters
import rpcnode.toolkit.chains.arc.infrastructure.ArcHostBinaries
import rpcnode.toolkit.chains.arc.infrastructure.ArcLayout
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Runs `arc-snapshots download` through the shipped script (`resources/chains/arc/snapshot.sh.tmpl`) for the wizard's
 * Snapshot step. Progress comes from the script's state file, the tool's own percentages in its log and, failing that,
 * the size written so far.
 */
class ArcSnapshotRunner
{
    private val log = LoggerFactory.getLogger(ArcSnapshotRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class Paths(
        val root: Path,
        val execution: Path,
        val consensus: Path,
        val marker: Path,
        val state: Path,
        val logFile: Path,
        val script: Path,
    )

    data class State(val phase: String, val pct: Double?, val detail: String, val error: String)

    fun layout(destDir: Path, kind: String): Paths
    {
        val root = destDir.toAbsolutePath().normalize()
        return Paths(
            root = root,
            execution = ArcLayout.execution(root),
            consensus = ArcLayout.consensus(root),
            marker = ArcLayout.snapshotMarker(root, kind),
            state = root.resolve(".toolkit").resolve("arc-snapshot-state.json"),
            logFile = root.resolve("logs").resolve("arc-snapshot.log"),
            script = root.resolve(".toolkit").resolve("arc-snapshot.sh"),
        )
    }

    /** `arc-snapshots` under the node dir (or its parent), extracted from the synced tarball when needed. */
    fun ensureTool(root: Path): Path?
    {
        for (dir in listOfNotNull(root, root.parent))
        {
            when (val r = ArcHostBinaries.ensure(dir))
            {
                is ArcHostBinaries.Result.Ok -> return r.binDir.resolve(ArcHostBinaries.SNAPSHOTS)
                is ArcHostBinaries.Result.Failed -> log.debug("arc-snapshots under {}: {}", dir, r.detail)
            }
        }
        return null
    }

    fun writeScript(paths: Paths, env: String, kind: String, tool: Path): Path
    {
        val cluster = ArcClusters.lookup(env)
        val body = HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("arc", "snapshot.sh.tmpl"),
            mapOf(
                "env" to q(cluster.env),
                "chain" to q(cluster.chain),
                "kind" to q(kind),
                "tool" to q(tool.toAbsolutePath().toString()),
                "exec" to q(paths.execution.toString()),
                "cons" to q(paths.consensus.toString()),
                "marker" to q(paths.marker.toString()),
                "state" to q(paths.state.toString()),
                "log" to q(paths.logFile.toString()),
            ),
        )
        Files.createDirectories(paths.script.parent)
        Files.createDirectories(paths.logFile.parent)
        Files.writeString(paths.script, body)
        try
        {
            Files.setPosixFilePermissions(
                paths.script,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
        }
        catch (_: Exception)
        {
            paths.script.toFile().setExecutable(true)
        }
        return paths.script
    }

    /** [env] is added to the agent's environment (the node's proxy variables). */
    fun startProcess(script: Path, env: Map<String, String> = emptyMap()): Process =
        ProcessBuilder("bash", script.toAbsolutePath().toString())
            .apply { environment().putAll(env) }
            .redirectErrorStream(true)
            .start()

    fun stopTool()
    {
        try
        {
            ProcessBuilder("pkill", "-f", "arc-snapshots.*download").redirectErrorStream(true).start().waitFor()
        }
        catch (e: Exception)
        {
            log.debug("pkill arc-snapshots: {}", e.message)
        }
    }

    fun markerReady(marker: Path): Boolean = Files.isRegularFile(marker)

    fun readState(statePath: Path): State?
    {
        if (!Files.isRegularFile(statePath))
        {
            return null
        }
        return try
        {
            val root = json.parseToJsonElement(Files.readString(statePath)).jsonObject
            State(
                phase = root["phase"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "download" },
                pct = root["pct"]?.jsonPrimitive?.doubleOrNull,
                detail = root["detail"]?.jsonPrimitive?.content?.trim().orEmpty(),
                error = root["error"]?.jsonPrimitive?.content?.trim().orEmpty(),
            )
        }
        catch (e: Exception)
        {
            log.debug("arc snapshot state parse: {}", e.message)
            null
        }
    }

    /** Script state, with the tool's own percentage (log) or the bytes written so far added to the detail. */
    fun readLiveProgress(paths: Paths): State?
    {
        val state = readState(paths.state)
        if (state?.phase.equals("error", ignoreCase = true) || state?.phase.equals("done", ignoreCase = true))
        {
            return state
        }
        val fromLog = lastPercent(tail(paths.logFile))
        val written = ArcSnapshotProgress.bytesUnder(listOf(paths.execution, paths.consensus))
        val detail = buildString {
            append(state?.detail?.ifBlank { null } ?: "Arc snapshot · downloading")
            if (written > 0)
            {
                append(" · ").append(ArcSnapshotProgress.format(written)).append(" on disk")
            }
        }
        return State(
            phase = state?.phase?.ifBlank { "download" } ?: "download",
            pct = fromLog ?: state?.pct?.takeIf { it > 0 },
            detail = detail,
            error = state?.error.orEmpty(),
        )
    }

    /** Script error for a failed run: the state's `error`, else its `detail` when the phase is `error`. */
    fun failureMessage(statePath: Path): String?
    {
        val state = readState(statePath) ?: return null
        return state.error.ifBlank { null }
            ?: state.detail.takeIf { state.phase.equals("error", ignoreCase = true) && it.isNotBlank() }
    }

    /** Last [max] lines arc-snapshots itself printed (script diag lines and progress bars dropped). */
    fun failureTail(logFile: Path, max: Int = 8): List<String> =
        tail(logFile).splitToSequence('\n')
            .map { ANSI.replace(it.substringAfterLast('\r'), "").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SNAPSHOT_DIAG") }
            .toList()
            .takeLast(max)

    /** The tool's own error line (`Error: …`), else its last line — what the panel shows next to FAILED. */
    fun failureReason(tail: List<String>): String? =
        tail.lastOrNull { it.startsWith("Error") } ?: tail.lastOrNull()

    private val ANSI = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

    private fun tail(file: Path): String =
        runCatching {
            if (!Files.isRegularFile(file)) return@runCatching ""
            val size = Files.size(file)
            Files.newInputStream(file).use { input ->
                if (size > 16_384) input.skip(size - 16_384)
                String(input.readAllBytes(), Charsets.UTF_8)
            }
        }.getOrDefault("")

    internal fun lastPercent(text: String): Double? =
        Regex("""(\d{1,3}(?:\.\d+)?)\s*%""").findAll(text).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()
            ?.takeIf { it in 0.0..100.0 }

    private fun q(raw: String): String = raw.trim().replace("'", "'\\''")
}

/** Disk-size helpers for the snapshot detail line. */
object ArcSnapshotProgress
{
    /** Sum of regular file sizes under [dirs]; stops after ~3 s (a restore writes tens of thousands of files). */
    fun bytesUnder(dirs: List<Path>): Long
    {
        val deadline = System.nanoTime() + 3_000_000_000L
        var total = 0L
        for (dir in dirs)
        {
            if (!Files.isDirectory(dir)) continue
            runCatching {
                Files.walk(dir).use { stream ->
                    val it = stream.iterator()
                    while (it.hasNext() && System.nanoTime() < deadline)
                    {
                        val p = it.next()
                        if (Files.isRegularFile(p)) total += Files.size(p)
                    }
                }
            }
        }
        return total
    }

    fun format(bytes: Long): String
    {
        val gib = bytes / 1_073_741_824.0
        return if (gib >= 1) "%.1f GiB".format(gib) else "%.0f MiB".format(bytes / 1_048_576.0)
    }
}
