package rpcnode.toolkit.agent.infrastructure.http

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.infrastructure.proc.EnsureHostCurl
import rpcnode.toolkit.agent.infrastructure.proc.EnsureHostDownloader
import rpcnode.toolkit.agent.infrastructure.proc.SystemdTransientDownload

/**
 * Resumable HTTP download to a local file.
 *
 * Production path (preferNative=true):
 * 1. Prefer **aria2c** multi-connection resume; else **curl -C -**
 * 2. Prefer a **detached systemd-run unit** so agent restarts do not SIGKILL the transfer
 * 3. Fall back to a child process when systemd-run is unavailable
 *
 * Java [HttpURLConnection] is only for unit tests ([preferNative]=false).
 */
class SnapshotHttpDownload(
    private val connectTimeoutMs: Int = 30_000,
    /** Idle read timeout for the Java fallback — resets while bytes keep flowing. */
    private val readTimeoutMs: Int = 300_000,
    private val maxAttempts: Int = Int.MAX_VALUE,
    /** Production always uses aria2/curl; tests set false to exercise the Java path. */
    private val preferNative: Boolean = true,
    /** Kept for call-site compatibility with older tests. */
    preferCurl: Boolean = true,
)
{
    private val log = LoggerFactory.getLogger(SnapshotHttpDownload::class.java)
    private val useNative = preferNative && preferCurl

    fun fetch(
        label: String,
        url: String,
        dest: Path,
        expectedBytes: Long?,
        isAborted: () -> Boolean = { false },
        onProcess: (Process) -> Unit = {},
        onUnit: (unit: String) -> Unit = {},
        onRetry: (attempt: Int, already: Long, reason: String) -> Unit = { _, _, _ -> },
        onProgress: (copied: Long, total: Long?) -> Unit,
    )
    {
        Files.createDirectories(dest.parent)
        val tool = if (useNative)
        {
            val t = EnsureHostDownloader.ensure()
            log.info(
                "{} using {} ({})",
                label,
                t.bin,
                if (SystemdTransientDownload.available()) "detached systemd unit" else "child process",
            )
            t
        }
        else
        {
            null
        }
        var attempt = 0
        while (true)
        {
            if (isAborted())
            {
                error("snapshot aborted")
            }
            attempt++
            try
            {
                if (tool != null)
                {
                    fetchNative(label, tool, url, dest, expectedBytes, isAborted, onProcess, onUnit, onProgress)
                }
                else
                {
                    fetchOnceJava(label, url, dest, expectedBytes, isAborted, onProgress)
                }
                return
            }
            catch (e: Exception)
            {
                if (isAborted() || e.message?.contains("aborted") == true)
                {
                    throw e
                }
                if (!isTransient(e) || attempt >= maxAttempts)
                {
                    throw e
                }
                val already = if (Files.isRegularFile(dest)) Files.size(dest) else 0L
                val reason = e.message?.trim()?.ifBlank { e.javaClass.simpleName } ?: e.javaClass.simpleName
                log.warn("{} attempt {} failed at {} bytes ({}), will resume", label, attempt, already, reason)
                onRetry(attempt, already, reason)
                sleepBackoff(attempt)
            }
        }
    }

    private fun fetchNative(
        label: String,
        tool: EnsureHostDownloader.Tool,
        url: String,
        dest: Path,
        expectedBytes: Long?,
        isAborted: () -> Boolean,
        onProcess: (Process) -> Unit,
        onUnit: (String) -> Unit,
        onProgress: (copied: Long, total: Long?) -> Unit,
    )
    {
        val already = if (Files.isRegularFile(dest)) Files.size(dest) else 0L
        if (expectedBytes != null && already > 0 && already >= expectedBytes)
        {
            onProgress(already, expectedBytes)
            return
        }
        onProgress(already, expectedBytes)
        val cmd = downloadCommand(tool, url, dest)
        log.info("{} {} resume from {}", label, tool.bin, formatBytes(already))
        if (SystemdTransientDownload.available())
        {
            fetchDetached(label, tool, cmd, dest, expectedBytes, isAborted, onUnit, onProgress)
        }
        else
        {
            fetchChild(label, tool, cmd, dest, expectedBytes, isAborted, onProcess, onProgress)
        }
    }

    private fun downloadCommand(tool: EnsureHostDownloader.Tool, url: String, dest: Path): List<String>
    {
        val abs = dest.toAbsolutePath()
        val dir = abs.parent?.toString() ?: "."
        val name = abs.fileName.toString()
        return when (tool)
        {
            EnsureHostDownloader.Tool.Aria2 -> listOf(
                "aria2c",
                "-c",
                "-x", "16",
                "-s", "16",
                "-k", "1M",
                "--max-tries=0",
                "--retry-wait=3",
                "--timeout=120",
                "--connect-timeout", (connectTimeoutMs / 1000).coerceAtLeast(5).toString(),
                "--lowest-speed-limit=1K",
                "--file-allocation=none",
                "--auto-file-renaming=false",
                "--allow-overwrite=true",
                "--summary-interval=0",
                "--console-log-level=notice",
                "-d", dir,
                "-o", name,
                url,
            )
            EnsureHostDownloader.Tool.Curl -> listOf(
                "curl",
                "-fL",
                "--connect-timeout", (connectTimeoutMs / 1000).coerceAtLeast(5).toString(),
                "--retry", "0",
                "--speed-limit", "1024",
                "--speed-time", "180",
                "-C", "-",
                "-o", abs.toString(),
                url,
            )
        }
    }

    private fun fetchDetached(
        label: String,
        tool: EnsureHostDownloader.Tool,
        cmd: List<String>,
        dest: Path,
        expectedBytes: Long?,
        isAborted: () -> Boolean,
        onUnit: (String) -> Unit,
        onProgress: (copied: Long, total: Long?) -> Unit,
    )
    {
        val unit = SystemdTransientDownload.unitName(label)
        onUnit(unit)
        SystemdTransientDownload.start(unit, cmd)
        val already = if (Files.isRegularFile(dest)) Files.size(dest) else 0L
        while (true)
        {
            if (isAborted())
            {
                SystemdTransientDownload.stop(
                    unit,
                    destHint = dest.parent?.parent?.toString() ?: dest.parent?.toString(),
                )
                error("snapshot aborted")
            }
            val size = if (Files.isRegularFile(dest)) Files.size(dest) else already
            onProgress(size, expectedBytes)
            val code = SystemdTransientDownload.exitStatus(unit)
        if (code != null)
            {
                try
                {
                    finishNative(label, tool.bin, code, dest, expectedBytes, SystemdTransientDownload.lastLogLine(unit))
                }
                finally
                {
                    SystemdTransientDownload.stop(unit, destHint = dest.parent?.parent?.toString())
                }
                return
            }
            Thread.sleep(1_000)
        }
    }

    private fun fetchChild(
        label: String,
        tool: EnsureHostDownloader.Tool,
        cmd: List<String>,
        dest: Path,
        expectedBytes: Long?,
        isAborted: () -> Boolean,
        onProcess: (Process) -> Unit,
        onProgress: (copied: Long, total: Long?) -> Unit,
    )
    {
        val already = if (Files.isRegularFile(dest)) Files.size(dest) else 0L
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        onProcess(proc)
        val errBuf = StringBuilder()
        val drain = Thread {
            try
            {
                proc.inputStream.bufferedReader().use { reader ->
                    while (true)
                    {
                        val line = reader.readLine() ?: break
                        if (errBuf.length < 4_000)
                        {
                            errBuf.append(line).append('\n')
                        }
                    }
                }
            }
            catch (_: Exception)
            {
            }
        }.also {
            it.isDaemon = true
            it.name = "dl-drain-$label"
            it.start()
        }
        try
        {
            while (proc.isAlive)
            {
                if (isAborted())
                {
                    proc.destroyForcibly()
                    error("snapshot aborted")
                }
                val size = if (Files.isRegularFile(dest)) Files.size(dest) else already
                onProgress(size, expectedBytes)
                proc.waitFor(1, TimeUnit.SECONDS)
            }
        }
        catch (e: Exception)
        {
            proc.destroyForcibly()
            throw e
        }
        drain.join(2_000)
        val tip = errBuf.toString().trim().lineSequence().lastOrNull().orEmpty()
        finishNative(label, tool.bin, proc.exitValue(), dest, expectedBytes, tip)
    }

    private fun finishNative(
        label: String,
        bin: String,
        code: Int,
        dest: Path,
        expectedBytes: Long?,
        tip: String,
    )
    {
        val copied = if (Files.isRegularFile(dest)) Files.size(dest) else 0L
        // curl 33 = HTTP range error — often means the partial already matches the object.
        if (bin == "curl" && code == 33 && expectedBytes != null && copied >= expectedBytes)
        {
            log.info("{} curl range OK (already complete)", label)
            return
        }
        // aria2 exit 0 = ok; some mirrors return 0 with full file even after resume quirks.
        if (code != 0)
        {
            error(
                "$bin exit $code at ${formatBytes(copied)}" +
                    if (tip.isNotBlank()) ": $tip" else "",
            )
        }
        if (expectedBytes != null && expectedBytes > 0 && copied < expectedBytes)
        {
            error("download incomplete: got $copied of $expectedBytes bytes ($bin finished early)")
        }
        log.info("{} finished via {} ({})", label, bin, formatBytes(copied))
    }

    private fun fetchOnceJava(
        label: String,
        url: String,
        dest: Path,
        expectedBytes: Long?,
        isAborted: () -> Boolean,
        onProgress: (copied: Long, total: Long?) -> Unit,
    )
    {
        val already = if (Files.isRegularFile(dest)) Files.size(dest) else 0L
        if (expectedBytes != null && already > 0 && already >= expectedBytes)
        {
            onProgress(already, expectedBytes)
            return
        }
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection)
        conn.instanceFollowRedirects = true
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("User-Agent", "rpcnode-agent")
        if (already > 0)
        {
            conn.setRequestProperty("Range", "bytes=$already-")
        }
        conn.connect()
        val code = conn.responseCode
        if (code == 416 && already > 0)
        {
            conn.disconnect()
            onProgress(already, expectedBytes ?: already)
            return
        }
        val append = already > 0 && code == 206
        if (code == 200 && already > 0)
        {
            // Server ignored Range — restart from zero.
            Files.deleteIfExists(dest)
        }
        if (code != 200 && code != 206)
        {
            conn.disconnect()
            error("download failed HTTP $code for $url")
        }
        val total = contentTotal(conn, if (append) already else 0L, expectedBytes, append)
        val options = if (append)
        {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
        }
        else
        {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        }
        try
        {
            conn.inputStream.use { input ->
                Files.newOutputStream(dest, *options).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var copied = if (append) already else 0L
                    var lastReport = copied
                    onProgress(copied, total)
                    while (true)
                    {
                        if (isAborted())
                        {
                            error("snapshot aborted")
                        }
                        val n = input.read(buf)
                        if (n < 0)
                        {
                            break
                        }
                        out.write(buf, 0, n)
                        copied += n
                        if (copied - lastReport >= REPORT_EVERY_BYTES)
                        {
                            lastReport = copied
                            onProgress(copied, total)
                        }
                    }
                    onProgress(copied, total)
                    if (total != null && total > 0 && copied < total)
                    {
                        error("download incomplete: got $copied of $total bytes (stream closed early)")
                    }
                }
            }
        }
        finally
        {
            conn.disconnect()
        }
        log.info("{} finished ({})", label, copiedHuman(dest))
    }

    private fun isTransient(e: Exception): Boolean
    {
        if (e is SocketTimeoutException || e is SocketException || e is IOException)
        {
            return true
        }
        val msg = e.message?.lowercase().orEmpty()
        val http = Regex("download failed http (\\d+)").find(msg)
        val code = http?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (code != null && (code == 408 || code == 425 || code == 429 || code in 500..599))
        {
            return true
        }
        val toolExit = Regex("(?:curl|aria2c) exit (\\d+)").find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()
        // curl: 18 partial, 28 timeout, 52 empty reply, 56 recv failure, 33 range (retry unless complete)
        // aria2: 2 timeout, 3 resource not found (sometimes transient CDN), 6 network, 7 unfinished
        if (toolExit != null && toolExit in setOf(2, 3, 6, 7, 18, 22, 28, 33, 35, 52, 55, 56, 137, 143))
        {
            return true
        }
        return "stream closed" in msg ||
            "connection reset" in msg ||
            "broken pipe" in msg ||
            "unexpected end" in msg ||
            "download incomplete" in msg ||
            "read timed out" in msg ||
            "timeout" in msg ||
            "temporarily unavailable" in msg ||
            "connection refused" in msg ||
            "network is unreachable" in msg ||
            "curl exit" in msg ||
            "aria2c exit" in msg ||
            "systemd-run failed" in msg
    }

    private fun sleepBackoff(attempt: Int)
    {
        val sec = when
        {
            attempt <= 1 -> 3L
            attempt <= 3 -> 10L
            attempt <= 6 -> 30L
            else -> 60L
        }
        try
        {
            Thread.sleep(sec * 1000L)
        }
        catch (_: InterruptedException)
        {
            Thread.currentThread().interrupt()
            error("snapshot aborted")
        }
    }

    private fun copiedHuman(dest: Path): String
    {
        val bytes = if (Files.isRegularFile(dest)) Files.size(dest) else 0L
        return formatBytes(bytes)
    }

    private fun contentTotal(
        conn: HttpURLConnection,
        already: Long,
        expectedBytes: Long?,
        append: Boolean,
    ): Long?
    {
        val len = conn.contentLengthLong
        if (len > 0)
        {
            return if (append) already + len else len
        }
        val range = conn.getHeaderField("Content-Range")
        if (!range.isNullOrBlank())
        {
            val totalPart = range.substringAfter('/').trim()
            totalPart.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        if (expectedBytes != null && expectedBytes > 0)
        {
            return expectedBytes
        }
        return null
    }

    companion object
    {
        private const val REPORT_EVERY_BYTES = 8L * 1024 * 1024

        fun formatBytes(bytes: Long): String = when
        {
            bytes >= 1024L * 1024 * 1024 -> "%.1f GiB".format(bytes.toDouble() / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes.toDouble() / 1024.0 / 1024.0)
            else -> "$bytes B"
        }

        fun curlAvailable(): Boolean = EnsureHostCurl.onPath("curl")

        fun unitNameForLabel(label: String): String = SystemdTransientDownload.unitName(label)
    }
}
