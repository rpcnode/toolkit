package rpcnode.toolkit.agent.infrastructure.http

import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import rpcnode.toolkit.chains.bsc.infrastructure.BscClusters

/**
 * Official `bnb-chain/bsc-snapshots` without staging archives: every part from the snapshot CSV is
 * piped `HTTP → tar -I lz4 -x` straight into the datadir, MD5 checked on the fly.
 *
 * fetch-snapshot.sh downloads a whole part (mainnet base ≈2 TB) to the snapshots disk before it
 * extracts; here the snapshots disk is not used at all. A dropped connection resumes with HTTP Range
 * into the same tar ([ResumableStreamCopier]); finished parts are recorded in [DONE_FILE], so an agent
 * restart re-streams only the part that was in flight.
 */
class BscStreamSnapshot(
    private val fetchText: (url: String) -> String = ::httpGetText,
    private val copierFor: (url: String, expectedBytes: Long?) -> ResumableStreamCopier =
        { url, expected -> ResumableStreamCopier(url, expected) },
)
{
    data class Part(val filename: String, val url: String, val md5: String, val sizeBytes: Long?)

    data class Plan(val name: String, val csvName: String, val parts: List<Part>)
    {
        val totalBytes: Long get() = parts.sumOf { it.sizeBytes ?: 0L }
    }

    /** Latest snapshot name from the README, then its CSV (`-pruneancient` unless [flavor] is `full`). */
    fun resolvePlan(env: String, flavor: String): Plan
    {
        val prefix = BscClusters.lookup(env).snapshotPrefix
        val readme = fetchText(README)
        val name = latestName(readme, prefix)
            ?: error("cannot resolve latest $prefix from bsc-snapshots README")
        val csvName = if (BscClusters.normalizeSnapshotFlavor(flavor) == "full") name else "$name-pruneancient"
        val parts = parseCsv(fetchText("$DIST/$csvName.csv"))
        if (parts.isEmpty())
        {
            error("bsc-snapshots $csvName.csv lists no archives")
        }
        return Plan(name = name, csvName = csvName, parts = parts)
    }

    /** Parts of [plan] already extracted into [dataDir] by an earlier run of the same snapshot. */
    fun donePartsFor(dataDir: Path, plan: Plan): Set<String>
    {
        val file = dataDir.resolve(DONE_FILE)
        if (!Files.isRegularFile(file))
        {
            return emptySet()
        }
        val lines = Files.readAllLines(file).map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull() != plan.csvName)
        {
            return emptySet()
        }
        return lines.drop(1).toSet()
    }

    /** New snapshot (or a different one than recorded): drop the old geth dir and start the record. */
    fun beginFresh(dataDir: Path, plan: Plan)
    {
        deleteRecursively(dataDir.resolve("geth"))
        Files.createDirectories(dataDir)
        Files.writeString(dataDir.resolve(DONE_FILE), plan.csvName + "\n")
    }

    fun markPartDone(dataDir: Path, part: Part)
    {
        Files.writeString(
            dataDir.resolve(DONE_FILE),
            part.filename + "\n",
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    fun clearDoneRecord(dataDir: Path)
    {
        Files.deleteIfExists(dataDir.resolve(DONE_FILE))
    }

    /**
     * Streams one part into [dataDir]. Archives nest under `server/data-seed/geth/…`: tar keeps only
     * members under `geth/` and rewrites the prefix so they land in `<datadir>/geth/…`.
     */
    fun extractPart(
        part: Part,
        dataDir: Path,
        isAborted: () -> Boolean,
        onProcess: (Process) -> Unit,
        onRetry: (attempt: Int, already: Long, reason: String) -> Unit,
        onProgress: (copied: Long, total: Long?) -> Unit,
    )
    {
        Files.createDirectories(dataDir)
        val tar = ProcessBuilder(tarCommand(dataDir))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()
        onProcess(tar)
        val stderr = StringBuffer()
        val errDrain = Thread({
            runCatching { tar.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') } }
        }, "bsc-stream-tar-stderr")
        errDrain.isDaemon = true
        errDrain.start()

        val md5 = MessageDigest.getInstance("MD5")
        val copier = copierFor(part.url, part.sizeBytes)
        try
        {
            DigestOutputStream(NonClosing(tar.outputStream), md5).use { sink ->
                copier.copy(sink, isAborted, onRetry, onProgress)
            }
            tar.outputStream.close()
        }
        catch (e: Exception)
        {
            tar.destroyForcibly()
            errDrain.join(2_000)
            if (e is ResumableStreamCopier.SinkFailed)
            {
                // tar stopped reading (corrupt archive, disk full…): its stderr says why.
                error("tar -I lz4 failed on ${part.filename}: ${stderr.toString().trim().ifBlank { e.message ?: "tar exited" }}")
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
        val code = tar.exitValue()
        if (code != 0)
        {
            error("tar -I lz4 failed on ${part.filename} (exit $code): ${stderr.toString().trim().ifBlank { "no stderr" }}")
        }
        val got = md5.digest().joinToString("") { "%02x".format(it) }
        if (part.md5.isNotBlank() && !got.equals(part.md5, ignoreCase = true))
        {
            error("MD5 mismatch on ${part.filename}: got $got, expected ${part.md5}")
        }
    }

    /** tar owns the pipe: closing the digest stream must not close it before tar's exit is read. */
    private class NonClosing(private val out: OutputStream) : OutputStream()
    {
        override fun write(b: Int) = out.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
        override fun close() = out.flush()
    }

    private fun deleteRecursively(path: Path)
    {
        if (!Files.exists(path))
        {
            return
        }
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object
    {
        const val README = "https://raw.githubusercontent.com/bnb-chain/bsc-snapshots/main/README.md"
        const val DIST = "https://raw.githubusercontent.com/bnb-chain/bsc-snapshots/main/dist"

        /** `<csv name>` then one finished part filename per line. */
        const val DONE_FILE = ".snapshot-stream-done"

        /** `server/data-seed/geth/chaindata/…` → `geth/chaindata/…` (GNU tar sed expression). */
        const val GETH_TRANSFORM = """s,^\([^/]*/\)*geth/,geth/,"""

        fun tarCommand(dataDir: Path): List<String> = listOf(
            "tar", "-I", "lz4", "-x", "-f", "-",
            "-C", dataDir.toString(),
            "--wildcards",
            "--transform", GETH_TRANSFORM,
            "*geth/*",
        )

        fun latestName(readme: String, prefix: String): String? =
            Regex(Regex.escape(prefix) + "-[0-9]{8}").find(readme)?.value

        /** `filename,URL,md5,size` rows; size is GiB-based (`1899.49GB` = 1899.49 × 2³⁰). */
        fun parseCsv(text: String): List<Part> =
            text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    val cols = line.split(',').map { it.trim() }
                    if (cols.size < 2 || cols[0].equals("filename", ignoreCase = true))
                    {
                        return@mapNotNull null
                    }
                    if (!cols[1].startsWith("http"))
                    {
                        return@mapNotNull null
                    }
                    Part(
                        filename = cols[0],
                        url = cols[1],
                        md5 = cols.getOrElse(2) { "" },
                        sizeBytes = parseSize(cols.getOrElse(3) { "" }),
                    )
                }
                .toList()

        fun parseSize(raw: String): Long?
        {
            val m = Regex("""^([0-9]+(?:\.[0-9]+)?)\s*([KMGT]?)i?B?$""", RegexOption.IGNORE_CASE)
                .find(raw.trim()) ?: return null
            val n = m.groupValues[1].toDoubleOrNull() ?: return null
            val mult = when (m.groupValues[2].uppercase())
            {
                "K" -> 1L shl 10
                "M" -> 1L shl 20
                "G" -> 1L shl 30
                "T" -> 1L shl 40
                else -> 1L
            }
            return (n * mult).toLong()
        }

        private val http: HttpClient by lazy {
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        }

        private fun httpGetText(url: String): String
        {
            var last: Exception? = null
            repeat(3) { attempt ->
                try
                {
                    val req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(120))
                        .GET()
                        .build()
                    val res = http.send(req, HttpResponse.BodyHandlers.ofString())
                    if (res.statusCode() in 200..299)
                    {
                        return res.body()
                    }
                    last = IllegalStateException("GET $url → HTTP ${res.statusCode()}")
                }
                catch (e: Exception)
                {
                    last = e
                }
                Thread.sleep(3_000L * (attempt + 1))
            }
            throw last ?: IllegalStateException("GET $url failed")
        }
    }
}
