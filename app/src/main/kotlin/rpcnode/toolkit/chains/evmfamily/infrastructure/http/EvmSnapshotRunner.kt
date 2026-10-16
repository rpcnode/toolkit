package rpcnode.toolkit.chains.evmfamily.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmHostBinaries
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfiles
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotPaths
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotRef
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotSentinel
import rpcnode.toolkit.chains.evmfamily.infrastructure.ScriptedSnapshot
import rpcnode.toolkit.chains.ScriptedSnapshots

/**
 * Runs the restore script of an `evm-snapshot://` snapshot (Tempo / Fluent `download`, Sonic genesis import, Berachain
 * archive pair) for the wizard's Snapshot step. The script writes `.toolkit/evm-snapshot-state.json`
 * (`{"phase","pct","detail"}`) and the marker file; this class only starts it and reads the state back.
 */
class EvmSnapshotRunner
{
    private val log = LoggerFactory.getLogger(EvmSnapshotRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class State(val phase: String, val pct: Double?, val detail: String, val error: String)

    fun profileFor(ref: EvmSnapshotRef): EvmProfile? = EvmProfiles.ALL.firstOrNull { it.network.value == ref.network }

    /** Any network with a scripted restore: the EVM-family profiles and Polygon. */
    fun snapshotFor(ref: EvmSnapshotRef): ScriptedSnapshot? = ScriptedSnapshots.ALL.firstOrNull { it.snapshotNetwork == ref.network }

    fun layout(destDir: Path, bins: Map<String, Path> = emptyMap()): EvmSnapshotPaths
    {
        val root = destDir.toAbsolutePath().normalize()
        return EvmSnapshotPaths(
            root = root,
            bins = bins,
            marker = EvmSnapshotSentinel.marker(root),
            state = root.resolve(".toolkit").resolve("evm-snapshot-state.json"),
            logFile = root.resolve("logs").resolve("evm-snapshot.log"),
            script = root.resolve(".toolkit").resolve("evm-snapshot.sh"),
        )
    }

    /** The binaries the restore needs, installed under `<dest>/bin` from the synced client files. */
    fun ensureBinaries(profile: ScriptedSnapshot, envId: String, root: Path): EvmHostBinaries.Result =
        EvmHostBinaries.ensure(root, profile.snapshotBinaries(envId))

    fun writeScript(profile: ScriptedSnapshot, ref: EvmSnapshotRef, paths: EvmSnapshotPaths): Path?
    {
        val body = profile.snapshotScript(ref, paths) ?: return null
        Files.createDirectories(paths.script.parent)
        Files.createDirectories(paths.logFile.parent)
        Files.writeString(paths.script, body)
        runCatching { Files.setPosixFilePermissions(paths.script, PosixFilePermissions.fromString("rwx------")) }
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
        runCatching { ProcessBuilder("pkill", "-f", "\\.toolkit/evm-snapshot\\.sh").redirectErrorStream(true).start().waitFor() }
            .onFailure { log.debug("pkill evm-snapshot: {}", it.message) }
    }

    fun markerReady(marker: Path): Boolean = Files.isRegularFile(marker)

    fun readState(statePath: Path): State?
    {
        if (!Files.isRegularFile(statePath)) return null
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
            log.debug("evm snapshot state parse: {}", e.message)
            null
        }
    }

    /** Script state; the detail line gets the bytes written under [paths] so far while the script cannot report a percentage. */
    fun readLiveProgress(paths: EvmSnapshotPaths): State?
    {
        val state = readState(paths.state)
        if (state?.phase.equals("error", ignoreCase = true) || state?.phase.equals("done", ignoreCase = true)) return state
        val written = EvmSnapshotProgress.bytesUnder(listOf(paths.root.resolve("data"), paths.root.resolve("reth"), paths.root.resolve("beacond")))
        val detail = buildString {
            append(state?.detail?.ifBlank { null } ?: "snapshot · working")
            if (written > 0) append(" · ").append(EvmSnapshotProgress.format(written)).append(" on disk")
        }
        return State(
            phase = state?.phase?.ifBlank { "download" } ?: "download",
            pct = state?.pct?.takeIf { it > 0 },
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

    /** Last [max] lines of the script log (ANSI colours and progress-bar carriage returns dropped). */
    fun failureTail(logFile: Path, max: Int = 8): List<String> =
        tail(logFile).splitToSequence('\n')
            .map { ANSI.replace(it.substringAfterLast('\r'), "").trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .takeLast(max)

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
}

/** Disk-size helpers for the snapshot detail line. */
object EvmSnapshotProgress
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
