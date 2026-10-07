package rpcnode.toolkit.cdn.infrastructure.http

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.slf4j.LoggerFactory

/**
 * GET with Range resume. Keeps the `.tmp` on disconnect. Connect / read timeouts abort a stall;
 * the caller retries and continues from the bytes already on disk.
 */
class ResumableHttpDownload(
    private val connectTimeoutMs: Int = 30_000,
    private val readTimeoutMs: Int = 120_000,
)
{
    private val log = LoggerFactory.getLogger(ResumableHttpDownload::class.java)

    fun fetch(label: String, url: String, dest: Path, expectedBytes: Long?)
    {
        val already = if (Files.isRegularFile(dest)) Files.size(dest) else 0L
        if (expectedBytes != null && already > 0 && already >= expectedBytes)
        {
            log.info("{} already on disk ({})", label, formatBytes(already))
            return
        }
        val conn = (URI(url).toURL().openConnection() as HttpURLConnection)
        conn.instanceFollowRedirects = true
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("User-Agent", "rpcnode-cdn")
        if (already > 0)
        {
            conn.setRequestProperty("Range", "bytes=$already-")
            log.info("resume {} from {}", label, formatBytes(already))
        }
        conn.connect()
        val code = conn.responseCode
        if (code == 416 && already > 0)
        {
            conn.disconnect()
            log.info("{} already on disk ({})", label, formatBytes(already))
            return
        }
        val append = already > 0 && code == 206
        if (code == 200 && already > 0)
        {
            Files.deleteIfExists(dest)
        }
        if (code != 200 && code != 206)
        {
            conn.disconnect()
            error("download failed HTTP $code for $url")
        }
        val total = contentTotal(conn, already, expectedBytes, append)
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
                    val buf = ByteArray(64 * 1024)
                    var copied = if (append) already else 0L
                    var lastPct = -1
                    var lastLogAt = 0L
                    logProgress(label, copied, total)
                    while (true)
                    {
                        val n = input.read(buf)
                        if (n < 0)
                        {
                            break
                        }
                        out.write(buf, 0, n)
                        copied += n
                        val now = System.currentTimeMillis()
                        val pct = if (total > 0) ((copied * 100) / total).toInt().coerceAtMost(100) else -1
                        val stepped = pct >= 0 && pct / 5 > lastPct / 5
                        val timed = now - lastLogAt >= 15_000
                        if (stepped || timed)
                        {
                            logProgress(label, copied, total)
                            lastPct = pct
                            lastLogAt = now
                        }
                    }
                    logProgress(label, copied, if (total > 0) total else copied)
                    if (total > 0 && copied < total)
                    {
                        error("download dropped: got $copied of $total for $url")
                    }
                }
            }
        }
        finally
        {
            conn.disconnect()
        }
    }

    private fun contentTotal(
        conn: HttpURLConnection,
        already: Long,
        expectedBytes: Long?,
        append: Boolean,
    ): Long
    {
        val range = conn.getHeaderField("Content-Range")
        if (range != null)
        {
            val slash = range.substringAfterLast('/', "")
            val n = slash.toLongOrNull()
            if (n != null && n > 0)
            {
                return n
            }
        }
        val length = conn.contentLengthLong
        if (length > 0)
        {
            return if (append) already + length else length
        }
        return expectedBytes ?: -1L
    }

    private fun logProgress(label: String, copied: Long, total: Long)
    {
        if (total > 0)
        {
            val pct = ((copied * 100) / total).toInt().coerceAtMost(100)
            log.info("download {} — {}% ({} / {})", label, pct, formatBytes(copied), formatBytes(total))
        }
        else
        {
            log.info("download {} — {} so far (size unknown)", label, formatBytes(copied))
        }
    }

    companion object
    {
        fun formatBytes(n: Long): String
        {
            if (n < 1024)
            {
                return "$n B"
            }
            val units = arrayOf("KB", "MB", "GB", "TB")
            var v = n.toDouble()
            var i = -1
            while (v >= 1024 && i < units.lastIndex)
            {
                v /= 1024
                i += 1
            }
            return "%.1f %s".format(v, units[i])
        }
    }
}
