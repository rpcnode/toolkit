package rpcnode.toolkit.cdn.presentation

import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.danger
import com.github.ajalt.mordant.terminal.prompt
import com.github.ajalt.mordant.terminal.success
import com.github.ajalt.mordant.terminal.warning
import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.cdn.infrastructure.filesystem.CdnMountLister
import rpcnode.toolkit.cdn.infrastructure.filesystem.MountPoint

/**
 * Mandatory interactive pick of SNAPSHOT_CDN_DIR (disk mount → directory).
 * Default directory is the process cwd (where the jar was launched).
 */
object CdnSnapshotDirPicker
{
    private const val MANUAL = "Enter path manually…"

    fun launchCwd(): Path =
        Path.of(System.getProperty("user.dir") ?: ".").toAbsolutePath().normalize()

    fun pick(
        terminal: Terminal = CdnTerminal.create(),
        mounts: List<MountPoint> = CdnMountLister.list(),
        pick: ((title: String, items: List<String>, initial: Int) -> Int?)? = null,
        askPath: ((String, String) -> String?)? = null,
        current: Path? = null,
    ): Path?
    {
        val defaultDir = (current ?: launchCwd()).toAbsolutePath().normalize()
        val choose = pick ?: { title, items, initial ->
            CdnTerminal.pickIndex(terminal, title, items, initial)
        }
        val promptPath = askPath ?: { label, default ->
            terminal.prompt(label, default = default)?.trim()?.ifEmpty { null }
        }
        terminal.println("Archives land under <dir>/snapshots/")
        terminal.println("Default directory: $defaultDir")
        if (mounts.isEmpty())
        {
            terminal.warning("no disk mounts found via df — enter a path")
            val raw = promptPath("Snapshot directory", defaultDir.toString()) ?: return null
            return validateAndCreate(Path.of(raw), terminal)
        }
        val labels = mounts.map { it.label } + MANUAL
        val initial = mounts.indexOfFirst { defaultDir.startsWith(it.path) }.takeIf { it >= 0 } ?: 0
        val idx = choose("Where to store snapshots (pick a disk)", labels, initial) ?: return null
        val base: Path = if (idx == mounts.size)
        {
            val raw = promptPath("Snapshot directory", defaultDir.toString()) ?: return null
            Path.of(raw)
        }
        else
        {
            val mount = mounts[idx].path
            val suggested = when
            {
                defaultDir.startsWith(mount) -> defaultDir
                mount.toString() == "/" -> defaultDir
                else -> mount.resolve("rpcnode-cdn")
            }
            val raw = promptPath(
                "Directory on $mount (archives → <dir>/snapshots)",
                suggested.toString(),
            ) ?: return null
            Path.of(raw)
        }
        return validateAndCreate(base, terminal)
    }

    fun validateAndCreate(path: Path, terminal: Terminal = CdnTerminal.create()): Path?
    {
        return when (val result = ensureWritable(path))
        {
            is EnsureResult.Ok ->
            {
                terminal.success("snapshot dir → ${result.path}  (files under ${result.path}/snapshots)")
                result.path
            }
            is EnsureResult.Err ->
            {
                terminal.danger(result.message)
                null
            }
        }
    }

    /** Non-interactive check for sync/status (no TTY styling). */
    fun ensureWritable(path: Path): EnsureResult
    {
        val dir = path.toAbsolutePath().normalize()
        return try
        {
            Files.createDirectories(dir)
            if (!Files.isDirectory(dir))
            {
                return EnsureResult.Err("not a directory: $dir")
            }
            if (!Files.isWritable(dir))
            {
                return EnsureResult.Err("not writable: $dir")
            }
            Files.createDirectories(dir.resolve("snapshots"))
            EnsureResult.Ok(dir)
        }
        catch (e: Exception)
        {
            EnsureResult.Err("cannot use $dir: ${e.message}")
        }
    }

    sealed interface EnsureResult
    {
        data class Ok(val path: Path) : EnsureResult
        data class Err(val message: String) : EnsureResult
    }

    fun saveToEnvFile(
        envFile: Path,
        snapshotDir: Path,
        pollSec: Long? = null,
        downloadJobs: Int? = null,
        targetsFile: Path? = null,
    )
    {
        val prev = CdnEnvFile.read(envFile)
        CdnEnvFile.write(
            envFile,
            CdnEnvValues(
                snapshotDir = snapshotDir.toAbsolutePath().normalize().toString(),
                pollSec = pollSec?.toString() ?: prev?.pollSec,
                downloadJobs = downloadJobs?.toString() ?: prev?.downloadJobs,
                targetsFile = targetsFile?.toString() ?: prev?.targetsFile,
            ),
        )
    }
}
