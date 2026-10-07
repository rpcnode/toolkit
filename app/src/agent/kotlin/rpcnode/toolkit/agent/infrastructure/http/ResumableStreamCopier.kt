package rpcnode.toolkit.agent.infrastructure.http

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Copies an HTTP body into [OutputStream] and keeps going when the network drops.
 *
 * On a broken or stalled connection it reconnects with `Range: bytes=<offset>-` and continues
 * writing at the same offset, so the consumer (a `tar -xzf -` process) sees one uninterrupted
 * byte stream. Only the bytes already delivered are never re-sent; the mirror must answer
 * `206 Partial Content` for the resume, otherwise copying fails instead of corrupting the stream.
 *
 * Retries are unlimited while bytes keep arriving; it gives up after [retryWindowMs] without any
 * progress (a long outage), or at once for errors a retry cannot fix (404, Range ignored, tar died).
 */
class ResumableStreamCopier(
    private val url: String,
    private val expectedBytes: Long? = null,
    /** Abort when no byte arrives for this long (half-open connection). */
    private val idleTimeoutMs: Long = 60_000,
    /** Give up after this long without a single new byte. */
    private val retryWindowMs: Long = 12L * 60 * 60 * 1000,
    private val backoffMs: (consecutiveFailures: Int) -> Long = { n -> minOf(60_000L, 1_000L shl minOf(n, 6)) },
    connectTimeoutMs: Long = 30_000,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
)
{
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    /** The consumer closed or failed — not a network problem, never retried. */
    class SinkFailed(cause: Throwable) : IOException("sink failed: ${cause.message}", cause)

    /** A retry cannot fix this (404, mirror ignores Range, …). */
    class Fatal(message: String) : IOException(message)

    /** Bytes written to the sink so far. */
    @Volatile
    var copied: Long = 0
        private set

    fun copy(
        sink: OutputStream,
        isAborted: () -> Boolean = { false },
        onRetry: (attempt: Int, already: Long, reason: String) -> Unit = { _, _, _ -> },
        onProgress: (copied: Long, total: Long?) -> Unit = { _, _ -> },
    )
    {
        var total: Long? = expectedBytes
        var attempt = 0
        var failures = 0
        var lastProgressAt = System.nanoTime()
        while (true)
        {
            if (isAborted())
            {
                throw IOException("snapshot aborted")
            }
            val before = copied
            var reason: String
            try
            {
                val opened = open(copied)
                total = opened.total ?: total
                opened.body.use { body ->
                    readInto(body, sink, isAborted) {
                        lastProgressAt = System.nanoTime()
                        onProgress(copied, total)
                    }
                }
                val want = total
                if (want == null || copied >= want)
                {
                    sink.flush()
                    return
                }
                reason = "connection closed at $copied of $want bytes"
            }
            catch (e: SinkFailed)
            {
                throw e
            }
            catch (e: Fatal)
            {
                throw e
            }
            catch (e: IOException)
            {
                if (e.message == "snapshot aborted")
                {
                    throw e
                }
                reason = e.message?.ifBlank { null } ?: e.javaClass.simpleName
            }
            if (copied > before)
            {
                failures = 0
                lastProgressAt = System.nanoTime()
            }
            else
            {
                failures++
            }
            val stalledMs = (System.nanoTime() - lastProgressAt) / 1_000_000
            if (stalledMs > retryWindowMs)
            {
                throw IOException("network down for ${stalledMs / 60_000} min without any data: $reason")
            }
            attempt++
            onRetry(attempt, copied, reason)
            val wait = backoffMs(failures)
            var slept = 0L
            while (slept < wait)
            {
                if (isAborted())
                {
                    throw IOException("snapshot aborted")
                }
                val slice = minOf(200L, wait - slept)
                sleep(slice)
                slept += slice
            }
        }
    }

    /** ETag / Last-Modified of the first response, sent back as `If-Range` on every resume. */
    @Volatile
    private var validator: String? = null

    private class Opened(val body: InputStream, val total: Long?)

    private fun open(offset: Long): Opened
    {
        val builder = HttpRequest.newBuilder(URI(url)).GET().header("User-Agent", "rpcnode-agent")
        if (offset > 0)
        {
            builder.header("Range", "bytes=$offset-")
            // If the file changed on the mirror the server answers 200 instead of 206 → we stop
            // instead of gluing two different archives together.
            validator?.let { builder.header("If-Range", it) }
        }
        val resp = try
        {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        }
        catch (e: InterruptedException)
        {
            Thread.currentThread().interrupt()
            throw IOException("snapshot aborted")
        }
        val status = resp.statusCode()
        when
        {
            status == 416 && offset > 0 ->
            {
                resp.body().close()
                val size = expectedBytes
                if (size != null && offset >= size)
                {
                    return Opened(InputStream.nullInputStream(), size)
                }
                throw Fatal("mirror answered 416 for offset $offset — cannot resume")
            }
            status in 500..599 || status == 408 || status == 429 ->
            {
                resp.body().close()
                throw IOException("HTTP $status")
            }
            status !in 200..299 ->
            {
                resp.body().close()
                throw Fatal("HTTP $status for $url")
            }
            offset > 0 && status != 206 ->
            {
                resp.body().close()
                throw Fatal("mirror ignores Range or the file changed (HTTP $status) — cannot resume the stream; start the snapshot again")
            }
        }
        if (offset > 0)
        {
            val range = resp.headers().firstValue("Content-Range").orElse("")
            val match = Regex("""bytes\s+(\d+)-(\d+)?/(\d+|\*)""").find(range)
            val start = match?.groupValues?.get(1)?.toLongOrNull()
            if (start != offset)
            {
                resp.body().close()
                throw Fatal("mirror resumed at ${start ?: "?"} instead of $offset (Content-Range: $range)")
            }
            return Opened(resp.body(), match?.groupValues?.get(3)?.toLongOrNull())
        }
        val length = resp.headers().firstValue("Content-Length").map { it.toLongOrNull() }.orElse(null)
        validator = resp.headers().firstValue("ETag").orElse(null)?.takeIf { !it.startsWith("W/") }
            ?: resp.headers().firstValue("Last-Modified").orElse(null)
        return Opened(resp.body(), length?.takeIf { it > 0 })
    }

    private fun readInto(body: InputStream, sink: OutputStream, isAborted: () -> Boolean, onChunk: () -> Unit)
    {
        val lastRead = java.util.concurrent.atomic.AtomicLong(System.nanoTime())
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        // A half-open connection blocks read() forever: close the body when nothing arrives.
        val watchdog = Thread({
            while (!done.get())
            {
                try
                {
                    Thread.sleep(1_000)
                }
                catch (_: InterruptedException)
                {
                    return@Thread
                }
                if ((System.nanoTime() - lastRead.get()) / 1_000_000 > idleTimeoutMs)
                {
                    runCatching { body.close() }
                    return@Thread
                }
            }
        }, "snapshot-stream-watchdog")
        watchdog.isDaemon = true
        watchdog.start()
        try
        {
            val buf = ByteArray(64 * 1024)
            while (true)
            {
                if (isAborted())
                {
                    throw IOException("snapshot aborted")
                }
                val n = try
                {
                    body.read(buf)
                }
                catch (e: IOException)
                {
                    throw IOException(
                        if ((System.nanoTime() - lastRead.get()) / 1_000_000 > idleTimeoutMs)
                        {
                            "no data for ${idleTimeoutMs / 1000}s"
                        }
                        else
                        {
                            e.message?.ifBlank { null } ?: e.javaClass.simpleName
                        },
                        e,
                    )
                }
                if (n < 0)
                {
                    return
                }
                lastRead.set(System.nanoTime())
                try
                {
                    sink.write(buf, 0, n)
                }
                catch (e: IOException)
                {
                    throw SinkFailed(e)
                }
                copied += n
                onChunk()
            }
        }
        finally
        {
            done.set(true)
            watchdog.interrupt()
        }
    }
}
