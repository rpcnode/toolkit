package rpcnode.toolkit.agent.infrastructure.http

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.application.client.ClientStagingLayout
import rpcnode.toolkit.agent.infrastructure.proc.EnsureHostCurl
import rpcnode.toolkit.chains.starknet.infrastructure.http.StarknetSnapshotResolver

/**
 * Snapshot archive → [destDir].
 *
 * - [streamUnpack]=true (TRON): HTTP → `tar -xzf -` in one pass (≈1× archive disk). A dropped
 *   connection is resumed with an HTTP Range request into the same tar ([ResumableStreamCopier]).
 * - [streamUnpack]=false: download to `{destDir}/.toolkit/snapshot-archive.tgz` with
 *   aria2/curl (resumable), then `tar -xzf`. Safer for multi-day mirrors.
 *
 * Clients and snapshot data share [destDir] (Kotlin node_dir layout). Clears remove
 * chain data (`database`, `output-directory`, …) but keep client artifacts
 * (`FullNode.jar`, `config.conf`, `VERSION`, `.toolkit/…`).
 */
class SnapshotStreamExtract(
    private val httpDownload: SnapshotHttpDownload = SnapshotHttpDownload(),
    private val streamCopier: (url: String, expectedBytes: Long?, proxy: String) -> ResumableStreamCopier =
        { url, expected, proxy -> ResumableStreamCopier(url, expected, proxy = proxy) },
)
{
    private val log = LoggerFactory.getLogger(SnapshotStreamExtract::class.java)

    fun fetchAndExtract(
        label: String,
        url: String,
        destDir: Path,
        expectedBytes: Long?,
        streamUnpack: Boolean = false,
        /** The node's proxy URL; blank = direct. */
        proxy: String = "",
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

        if (streamUnpack)
        {
            fetchAndExtractStreaming(
                label = label,
                url = url,
                dest = dest,
                expectedBytes = expectedBytes,
                proxy = proxy,
                onProcess = onProcess,
                isAborted = isAborted,
                onRetry = onRetry,
                onPhase = onPhase,
                onProgress = onProgress,
            )
            return
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
            proxy = proxy,
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
        runTarExtract(archive, dest, onProcess, isAborted, onProgress)
        Files.deleteIfExists(archive)
        flattenNestedOutputDirectory(dest)
        log.info("{} extracted into {}", label, dest)
    }

    /**
     * HTTP body → gzip/tar extract into [dest] without staging the archive on disk.
     *
     * The body is copied by [ResumableStreamCopier]: when the network drops it reconnects with an
     * HTTP Range request and keeps feeding the same `tar` process, so a multi-day multi-terabyte
     * snapshot survives disconnects and mirror restarts. Not resumable across an agent restart —
     * tar's state lives in the process.
     */
    private fun fetchAndExtractStreaming(
        label: String,
        url: String,
        dest: Path,
        expectedBytes: Long?,
        proxy: String,
        onProcess: (Process) -> Unit,
        isAborted: () -> Boolean,
        onRetry: (attempt: Int, already: Long, reason: String) -> Unit,
        onPhase: (phase: String) -> Unit,
        onProgress: (copied: Long, total: Long?) -> Unit,
    )
    {
        if (!EnsureHostCurl.onPath("tar"))
        {
            error("tar is required for stream unpack")
        }
        onPhase("download")
        clearDestEntirely(dest)
        Files.createDirectories(dest)

        val decompress = streamTarArgs(url)
        val tool = decompress.firstOrNull { it == "lz4" || it == "zstd" }
        if (tool != null && !EnsureHostCurl.onPath(tool))
        {
            error("$tool is required to unpack this snapshot (install the $tool package on the host)")
        }
        val tar = ProcessBuilder(listOf("tar") + decompress + listOf("-C", dest.toString()))
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        onProcess(tar)
        val stderr = StringBuffer()
        val errDrain = Thread({
            runCatching { tar.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') } }
        }, "$label-tar-stderr")
        errDrain.isDaemon = true
        errDrain.start()

        val copier = streamCopier(url, expectedBytes, proxy)
        try
        {
            copier.copy(tar.outputStream, isAborted, onRetry, onProgress)
            tar.outputStream.close()
        }
        catch (e: Exception)
        {
            tar.destroyForcibly()
            errDrain.join(2_000)
            if (e is ResumableStreamCopier.SinkFailed)
            {
                // tar stopped reading (corrupt archive, disk full…): its stderr says why.
                error("tar stream extract failed: ${stderr.toString().trim().ifBlank { e.message ?: "tar exited" }}")
            }
            throw e
        }

        while (tar.isAlive)
        {
            if (isAborted())
            {
                tar.destroyForcibly()
                error("snapshot aborted")
            }
            tar.waitFor(200, TimeUnit.MILLISECONDS)
        }
        errDrain.join(5_000)
        val tarCode = tar.exitValue()
        if (tarCode != 0)
        {
            error("tar stream extract failed (exit $tarCode): ${stderr.toString().trim().ifBlank { "no stderr" }}")
        }

        flattenNestedOutputDirectory(dest)
        onProgress(copier.copied.coerceAtLeast(expectedBytes ?: copier.copied), expectedBytes)
        log.info("{} stream-unpacked into {} ({} bytes)", label, dest, copier.copied)
    }

    private fun clearDestEntirely(destDir: Path)
    {
        clearChainDataPreservingClients(destDir, keepArchive = null)
    }

    private fun clearDestKeepingArchive(destDir: Path, archive: Path)
    {
        clearChainDataPreservingClients(destDir, keepArchive = archive)
    }

    /**
     * Wipe previous snapshot / chain DB leaves under [destDir], but never remove
     * client binaries/config synced by the Clients step (same node_dir).
     */
    private fun clearChainDataPreservingClients(destDir: Path, keepArchive: Path?)
    {
        if (!Files.isDirectory(destDir))
        {
            return
        }
        Files.list(destDir).use { children ->
            children.forEach { child ->
                if (shouldPreserveAlongsideSnapshot(child))
                {
                    return@forEach
                }
                deleteRecursively(child)
            }
        }
        if (keepArchive != null)
        {
            Files.createDirectories(keepArchive.parent)
        }
    }

    /** Top-level client files and `.toolkit` (launch metadata, client staging, archive). */
    private fun shouldPreserveAlongsideSnapshot(child: Path): Boolean =
        Companion.shouldPreserveAlongsideSnapshot(child)

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
        onProgress: (copied: Long, total: Long?) -> Unit,
    )
    {
        // The archive is fed to tar through stdin so the bytes consumed so far are known:
        // compressed bytes read / archive size is an exact progress for the extract phase
        // (tar itself reports nothing, and the unpacked size is not known up front).
        val total = Files.size(archive)
        onProgress(0L, total)
        val proc = ProcessBuilder("tar", "-xzf", "-", "-C", destDir.toString())
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        onProcess(proc)
        val fed = AtomicLong(0L)
        val stderr = StringBuffer()
        val errDrain = Thread({
            runCatching { proc.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') } }
        }, "snapshot-tar-stderr")
        errDrain.isDaemon = true
        errDrain.start()
        val feeder = Thread({
            try
            {
                Files.newInputStream(archive).use { input ->
                    proc.outputStream.use { output ->
                        val buf = ByteArray(1024 * 1024)
                        while (true)
                        {
                            val n = input.read(buf)
                            if (n < 0)
                            {
                                break
                            }
                            output.write(buf, 0, n)
                            fed.addAndGet(n.toLong())
                        }
                    }
                }
            }
            catch (_: IOException)
            {
                // tar exited early (bad archive / disk full): its exit code and stderr tell why.
            }
        }, "snapshot-tar-feed")
        feeder.isDaemon = true
        feeder.start()
        try
        {
            while (proc.isAlive)
            {
                if (isAborted())
                {
                    proc.destroyForcibly()
                    error("snapshot aborted")
                }
                proc.waitFor(500, TimeUnit.MILLISECONDS)
                onProgress(fed.get().coerceAtMost(total), total)
            }
        }
        catch (e: Exception)
        {
            proc.destroyForcibly()
            throw e
        }
        feeder.join(5_000)
        errDrain.join(5_000)
        val err = stderr.toString().trim()
        val code = proc.exitValue()
        if (code != 0)
        {
            error("tar extract failed (exit $code): ${err.ifBlank { "no stderr" }}")
        }
        onProgress(total, total)
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

        /** tar arguments (without `-C`) that read the archive from stdin; the compression follows the URL suffix. */
        fun streamTarArgs(url: String): List<String>
        {
            val path = url.trim().substringBefore('?').substringBefore('#').lowercase()
            return when
            {
                // Juno's mirror serves the tar.zst from `/files/<name>/latest` (no suffix, no redirect)
                StarknetSnapshotResolver.isOfficialUrl(url) -> listOf("-I", "zstd", "-xf", "-")
                path.endsWith(".tar.lz4") || path.endsWith(".lz4") -> listOf("-I", "lz4", "-xf", "-")
                path.endsWith(".tar.zst") || path.endsWith(".tar.zstd") -> listOf("-I", "zstd", "-xf", "-")
                path.endsWith(".tar") -> listOf("-xf", "-")
                else -> listOf("-xzf", "-")
            }
        }

        private val DB_FILE = Regex("""^(\d+\.(sst|log|blob)|MANIFEST-\d+|OPTIONS-\d+|CURRENT|LOCK|marker\..+)$""")

        /** Pebble / RocksDB file names: a stale one left by an earlier extract would corrupt the next snapshot. */
        fun isKeyValueDbFile(name: String): Boolean = DB_FILE.matches(name)

        /** Top-level client files and `.toolkit` survive snapshot clear / stop wipe. */
        fun shouldPreserveAlongsideSnapshot(child: Path): Boolean
        {
            val name = child.fileName?.toString() ?: return false
            if (name == ".toolkit")
            {
                return true
            }
            if (!Files.isRegularFile(child))
            {
                return false
            }
            // Pebble / RocksDB files at the top of the DB directory (Juno snapshots are flat) belong to the chain data
            if (isKeyValueDbFile(name))
            {
                return false
            }
            return ClientStagingLayout.isClientArtifactName(name) ||
                name.equals("VERSION", ignoreCase = true)
        }
    }
}
