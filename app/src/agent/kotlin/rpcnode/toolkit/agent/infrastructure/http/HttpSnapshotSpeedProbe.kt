package rpcnode.toolkit.agent.infrastructure.http

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.agent.application.snapshot.SnapshotSpeedProbe
import rpcnode.toolkit.agent.application.snapshot.SnapshotSpeedProbeReading

/**
 * Range GET for a small prefix of the archive. Bytes are read and discarded so the host can
 * measure mirror throughput without writing a snapshot file.
 */
class HttpSnapshotSpeedProbe(
    private val sampleBytes: Long = 1L shl 20,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 45_000,
) : SnapshotSpeedProbe
{
    override suspend fun probe(url: String): SnapshotSpeedProbeReading = withContext(Dispatchers.IO) {
        val target = url.trim()
        if (target.isEmpty())
        {
            return@withContext SnapshotSpeedProbeReading(
                available = false,
                detail = "URL is empty",
            )
        }
        val connectStart = System.nanoTime()
        try
        {
            val conn = (URI(target).toURL().openConnection() as HttpURLConnection)
            conn.instanceFollowRedirects = true
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", "rpcnode-agent-snapshot-probe")
            if (sampleBytes > 0)
            {
                conn.setRequestProperty("Range", "bytes=0-${sampleBytes - 1}")
            }
            conn.connect()
            val latencyMs = (System.nanoTime() - connectStart) / 1_000_000
            val code = conn.responseCode
            if (code !in 200 until 300)
            {
                conn.disconnect()
                return@withContext SnapshotSpeedProbeReading(
                    available = false,
                    latencyMs = latencyMs,
                    detail = "HTTP $code",
                )
            }
            val downloadStart = System.nanoTime()
            var read = 0L
            conn.inputStream.use { input ->
                val buf = ByteArray(128 * 1024)
                while (read < sampleBytes)
                {
                    val want = minOf(buf.size.toLong(), sampleBytes - read).toInt()
                    val n = input.read(buf, 0, want)
                    if (n <= 0)
                    {
                        break
                    }
                    read += n
                }
            }
            conn.disconnect()
            if (read <= 0L)
            {
                return@withContext SnapshotSpeedProbeReading(
                    available = true,
                    latencyMs = latencyMs,
                    detail = "Connected but no sample bytes read",
                )
            }
            val elapsedNs = System.nanoTime() - downloadStart
            val bytesPerSec = read * 1_000_000_000L / maxOf(elapsedNs, 1L)
            SnapshotSpeedProbeReading(
                available = true,
                bytesPerSec = bytesPerSec,
                sampleBytes = read,
                latencyMs = latencyMs,
                detail = "Host sample ${humanBytes(read)}",
            )
        }
        catch (e: Exception)
        {
            SnapshotSpeedProbeReading(
                available = false,
                detail = e.message?.ifBlank { null } ?: e.javaClass.simpleName,
            )
        }
    }

    companion object
    {
        private fun humanBytes(bytes: Long): String =
            when
            {
                bytes >= 1024L * 1024 -> "%.1f MiB".format(bytes.toDouble() / 1024.0 / 1024.0)
                bytes >= 1024L -> "%.0f KiB".format(bytes.toDouble() / 1024.0)
                else -> "$bytes B"
            }
    }
}
