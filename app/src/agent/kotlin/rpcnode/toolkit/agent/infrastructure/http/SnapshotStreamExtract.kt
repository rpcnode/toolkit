package rpcnode.toolkit.agent.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.slf4j.LoggerFactory

/**
 * Downloads the archive to `{destDir}/.toolkit/snapshot-archive.tgz` with aria2/curl
 * (OS package installed on demand; preferably a detached systemd unit), then extracts
 * with `tar` into [destDir]. Streaming HTTP→tar is intentionally avoided: multi-day
 * mirrors drop connections and pipe mode cannot resume.
 */
class SnapshotStreamExtract(
    private val httpDownload: SnapshotHttpDownload = SnapshotHttpDownload(),
)
{
    private val log = LoggerFactory.getLogger(SnapshotStreamExtract::class.java)

    fun fetchAndExtract(
        label: String,
        url: String,
        destDir: Path,
        expectedBytes: Long?,
        onProcess: (Process) -> Unit = {},
        onUnit: (unit: String) -> Unit = {},
        isAborted: () -> Boolean = { false },
        onRetry: (attempt: Int, already: Long, reason: String) -> Unit = { _, _, _ -> },
        onPhase: (phase: String) -> Unit = {},
        onProgress: (copied: Long, total: Long?) -> Unit,
    )
    {
        val dest = try
        {
            SnapshotDestDirPrep.ensureWritable(destDir)
        }
        catch (e: Exception)
        {
            val msg = e.message?.trim().orEmpty()
            if (msg.startsWith("cannot create dest_dir") || msg.startsWith("dest_dir "))
            {
                throw e
            }
            error(SnapshotDestDirPrep.formatThrowable("prepare dest_dir", destDir, e))
        }

        val toolkit = dest.resolve(".toolkit")
        Files.createDirectories(toolkit)
        val archive = toolkit.resolve(ARCHIVE_NAME)

        onPhase("download")
        httpDownload.fetch(
            label = label,
            url = url,
            dest = archive,
            expectedBytes = expectedBytes,
            isAborted = isAborted,
            onRetry = onRetry,
            onProcess = onProcess,
            onUnit = onUnit,
            onProgress = onProgress,
        )
        if (isAborted())
        {
            error("snapshot aborted")
        }

        onPhase("extract")
        clearDestKeepingArchive(dest, archive)
        runTarExtract(archive, dest, onProcess, isAborted)
        Files.deleteIfExists(archive)
        flattenNestedOutputDirectory(dest)
        log.info("{} extracted into {}", label, dest)
    }

    private fun clearDestKeepingArchive(destDir: Path, archive: Path)
    {
        if (!Files.isDirectory(destDir))
        {
            return
        }
        val toolkit = archive.parent
        Files.list(destDir).use { children ->
            children.forEach { child ->
                if (toolkit != null && child == toolkit)
                {
                    if (Files.isDirectory(child))
                    {
                        Files.list(child).use { inner ->
                            inner.forEach { file ->
                                if (file != archive)
                                {
                                    deleteRecursively(file)
                                }
                            }
                        }
                    }
                }
                else
                {
                    deleteRecursively(child)
                }
            }
        }
        Files.createDirectories(archive.parent)
    }

    private fun deleteRecursively(path: Path)
    {
        if (!Files.exists(path))
        {
            return
        }
        if (Files.isDirectory(path))
        {
            Files.walk(path).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
        else
        {
            Files.deleteIfExists(path)
        }
    }

    private fun runTarExtract(
        archive: Path,
        destDir: Path,
        onProcess: (Process) -> Unit,
        isAborted: () -> Boolean,
    )
    {
        val proc = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", destDir.toString())
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        onProcess(proc)
        try
        {
            while (proc.isAlive)
            {
                if (isAborted())
                {
                    proc.destroyForcibly()
                    error("snapshot aborted")
                }
                proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
        catch (e: Exception)
        {
            proc.destroyForcibly()
            throw e
        }
        val err = proc.errorStream.bufferedReader().readText().trim()
        val code = proc.exitValue()
        if (code != 0)
        {
            error("tar extract failed (exit $code): ${err.ifBlank { "no stderr" }}")
        }
    }

    /**
     * Official TRON tarballs nest under `output-directory/` — lift contents into [destDir]
     * so the disk-layout path is the node data root (still only inside destDir).
     */
    fun flattenNestedOutputDirectory(destDir: Path)
    {
        val nested = destDir.resolve("output-directory")
        if (!Files.isDirectory(nested))
        {
            return
        }
        Files.list(nested).use { stream ->
            stream.forEach { from ->
                val to = destDir.resolve(from.fileName.toString())
                if (Files.exists(to))
                {
                    if (Files.isDirectory(to))
                    {
                        Files.walk(to).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                    }
                    else
                    {
                        Files.deleteIfExists(to)
                    }
                }
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        Files.deleteIfExists(nested)
    }

    companion object
    {
        const val ARCHIVE_NAME = "snapshot-archive.tgz"
    }
}
