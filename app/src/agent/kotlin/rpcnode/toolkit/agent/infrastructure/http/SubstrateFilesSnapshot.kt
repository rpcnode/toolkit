package rpcnode.toolkit.agent.infrastructure.http

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import rpcnode.toolkit.chains.substrate.infrastructure.http.SubstrateSnapshotResolver
import rpcnode.toolkit.shared.infrastructure.http.HttpProxy

/**
 * Parity / IBP database snapshot (`snapshots.polkadot.io`): a directory of files described by `files.txt`, copied here
 * into `<dest>/chains/<chain id>/<paritydb|db>/` with [parallel] resumable GETs — what the official
 * `rclone copy --transfers 20 --files-from files.txt --size-only` recipe does, without rclone on the host.
 *
 * A file is skipped when the remote `Content-Length` equals the local size (rclone `--size-only`); a file that was still
 * downloading when the agent stopped restarts from its start (files are at most ~1 GB; drops inside one run are resumed
 * with HTTP Range by [ResumableStreamCopier]). The store publishes no checksums, so completeness = every manifest file
 * present at its published size.
 */
class SubstrateFilesSnapshot(
    private val parallel: Int = PARALLEL,
    private val fetchText: (url: String, proxy: String) -> String = ::httpGetText,
    private val remoteSize: (url: String, proxy: String) -> Long? = ::httpHeadLength,
    private val copierFor: (url: String, expectedBytes: Long?, proxy: String) -> ResumableStreamCopier =
        { url, expected, proxy -> ResumableStreamCopier(url, expected, proxy = proxy) },
)
{
    data class Progress(val doneBytes: Long, val totalBytes: Long, val filesDone: Int, val filesTotal: Int, val phase: String)
    {
        val pct: Double get() = if (totalBytes > 0) (doneBytes * 100.0 / totalBytes).coerceIn(0.0, 100.0) else 0.0
    }

    data class Layout(val dbDir: Path, val stateFile: Path, val readyFile: Path)

    fun layout(dest: Path, ref: SubstrateSnapshotResolver.Ref): Layout
    {
        val root = dest.toAbsolutePath().normalize()
        return Layout(
            dbDir = root.resolve("chains").resolve(ref.chainDirId).resolve(ref.db.dirName),
            stateFile = root.resolve(".toolkit").resolve(STATE_FILE),
            readyFile = root.resolve(".snapshot-ready"),
        )
    }

    /** True when this exact snapshot was restored completely (a re-run is a no-op). */
    fun alreadyReady(layout: Layout, ref: SubstrateSnapshotResolver.Ref): Boolean =
        Files.isRegularFile(layout.readyFile) &&
            runCatching { Files.readAllLines(layout.stateFile).firstOrNull()?.trim() }.getOrNull() == ref.baseUrl &&
            Files.isDirectory(layout.dbDir)

    /** Manifest lines → relative paths; anything that could leave the DB directory is rejected. */
    fun parseManifest(text: String): List<String>
    {
        val out = LinkedHashSet<String>()
        for (raw in text.lineSequence())
        {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (!SAFE_FILE.matches(line) || line.contains("..") || line.startsWith("/"))
            {
                throw IllegalStateException("files.txt lists an unsafe path: ${line.take(80)}")
            }
            out += line
        }
        return out.toList()
    }

    /**
     * Restores the snapshot of [ref] into [dest]. Blocks until done; throws on failure. [onProgress] is called about
     * once a second from the calling thread.
     */
    fun run(
        ref: SubstrateSnapshotResolver.Ref,
        dest: Path,
        proxy: String,
        isAborted: () -> Boolean,
        onLog: (String) -> Unit,
        onProgress: (Progress) -> Unit,
    )
    {
        val lay = layout(dest, ref)
        Files.createDirectories(lay.stateFile.parent)
        if (alreadyReady(lay, ref))
        {
            onLog("snapshot ${ref.baseUrl} is already restored")
            return
        }
        val manifest = parseManifest(fetchText("${ref.baseUrl}/files.txt", proxy))
        if (manifest.isEmpty())
        {
            throw IllegalStateException("files.txt of ${ref.baseUrl} lists no files")
        }
        val sameSnapshot = runCatching { Files.readAllLines(lay.stateFile).firstOrNull()?.trim() }.getOrNull() == ref.baseUrl
        Files.deleteIfExists(lay.readyFile)
        if (!sameSnapshot)
        {
            // another snapshot (or none): the node's old database must not be mixed with the new files
            deleteRecursively(lay.dbDir)
            Files.writeString(lay.stateFile, ref.baseUrl + "\n")
        }
        Files.createDirectories(lay.dbDir)
        onLog("${manifest.size} file(s) from ${ref.baseUrl} → ${lay.dbDir} (${parallel} parallel)")

        val failure = AtomicReference<Throwable?>(null)
        val stop = { failure.get() != null || isAborted() }
        val pool = Executors.newFixedThreadPool(parallel.coerceIn(1, 64)) { r -> Thread(r, "substrate-snapshot").also { it.isDaemon = true } }

        // 1. sizes (HEAD): skip decisions and an exact total
        val sizes = LongArray(manifest.size) { -1L }
        val headsDone = AtomicInteger(0)
        manifest.forEachIndexed { i, rel ->
            pool.execute {
                if (stop()) return@execute
                try
                {
                    sizes[i] = remoteSize("${ref.baseUrl}/$rel", proxy) ?: throw IOException("no Content-Length for $rel")
                }
                catch (e: Throwable)
                {
                    failure.compareAndSet(null, e)
                }
                headsDone.incrementAndGet()
            }
        }
        while (headsDone.get() < manifest.size && failure.get() == null)
        {
            if (isAborted()) break
            onProgress(Progress(0, 0, 0, manifest.size, "sizes"))
            Thread.sleep(500)
        }
        if (failure.get() == null && !isAborted())
        {
            // 2. files
            val total = sizes.sum()
            val completed = AtomicLong(0)
            val inFlight = AtomicLong(0)
            val filesDone = AtomicInteger(0)
            val finished = AtomicInteger(0)
            manifest.forEachIndexed { i, rel ->
                pool.execute {
                    try
                    {
                        if (!stop())
                        {
                            val target = lay.dbDir.resolve(rel)
                            val want = sizes[i]
                            if (Files.isRegularFile(target) && Files.size(target) == want)
                            {
                                completed.addAndGet(want)
                                filesDone.incrementAndGet()
                            }
                            else
                            {
                                download("${ref.baseUrl}/$rel", target, want, proxy, stop, inFlight)
                                completed.addAndGet(want)
                                filesDone.incrementAndGet()
                            }
                        }
                    }
                    catch (e: Throwable)
                    {
                        failure.compareAndSet(null, e)
                    }
                    finally
                    {
                        finished.incrementAndGet()
                    }
                }
            }
            while (finished.get() < manifest.size)
            {
                if (isAborted()) break
                onProgress(Progress(completed.get() + inFlight.get(), total, filesDone.get(), manifest.size, "files"))
                Thread.sleep(1_000)
            }
            onProgress(Progress(completed.get() + inFlight.get(), total, filesDone.get(), manifest.size, "files"))
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        if (isAborted())
        {
            throw IllegalStateException("snapshot aborted")
        }
        failure.get()?.let { throw IllegalStateException("snapshot download failed: ${it.message ?: it.javaClass.simpleName}", it) }
        val missing = manifest.firstOrNull { !Files.isRegularFile(lay.dbDir.resolve(it)) }
        if (missing != null)
        {
            throw IllegalStateException("snapshot incomplete: $missing is missing")
        }
        Files.writeString(lay.readyFile, ref.baseUrl + "\n")
    }

    private fun download(url: String, target: Path, expected: Long, proxy: String, stop: () -> Boolean, inFlight: AtomicLong)
    {
        Files.createDirectories(target.parent)
        val part = target.resolveSibling(target.fileName.toString() + ".part")
        Files.deleteIfExists(part)
        var reported = 0L
        try
        {
            Files.newOutputStream(part).buffered(1 shl 20).use { sink ->
                copierFor(url, expected, proxy).copy(
                    sink = sink,
                    isAborted = stop,
                    onProgress = { copied, _ ->
                        inFlight.addAndGet(copied - reported)
                        reported = copied
                    },
                )
            }
            if (Files.size(part) != expected)
            {
                throw IOException("${target.fileName}: got ${Files.size(part)} bytes, expected $expected")
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        }
        finally
        {
            inFlight.addAndGet(-reported)
            Files.deleteIfExists(part)
        }
    }

    private fun deleteRecursively(dir: Path)
    {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    companion object
    {
        /** The official recipe uses 20 rclone transfers. */
        const val PARALLEL = 16
        const val STATE_FILE = "substrate-snapshot.url"
        private val SAFE_FILE = Regex("^[A-Za-z0-9._][A-Za-z0-9._/-]{0,200}$")

        /** One client per proxy: thousands of HEADs must not each build a client (and its thread pool). */
        private val clients = java.util.concurrent.ConcurrentHashMap<String, HttpClient>()

        private fun client(proxy: String): HttpClient =
            clients.computeIfAbsent(proxy.trim()) { p ->
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .version(HttpClient.Version.HTTP_1_1)
                    .apply { HttpProxy.apply(this, p) }
                    .build()
            }

        fun httpGetText(url: String, proxy: String): String
        {
            val req = HttpRequest.newBuilder(URI(url)).GET().timeout(Duration.ofSeconds(60)).header("User-Agent", "rpcnode-agent").build()
            val resp = client(proxy).send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) throw IOException("HTTP ${resp.statusCode()} for $url")
            return resp.body()
        }

        fun httpHeadLength(url: String, proxy: String): Long?
        {
            val req = HttpRequest.newBuilder(URI(url)).method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(60)).header("User-Agent", "rpcnode-agent").build()
            val resp = client(proxy).send(req, HttpResponse.BodyHandlers.discarding())
            if (resp.statusCode() !in 200..299) throw IOException("HTTP ${resp.statusCode()} for $url")
            return resp.headers().firstValue("Content-Length").map { it.toLongOrNull() }.orElse(null)
        }
    }
}
