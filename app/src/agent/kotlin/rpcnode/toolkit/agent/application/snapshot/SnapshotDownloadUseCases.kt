package rpcnode.toolkit.agent.application.snapshot

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.domain.model.SnapshotJob
import rpcnode.toolkit.agent.infrastructure.http.SnapshotDestDirPrep
import rpcnode.toolkit.agent.infrastructure.http.SnapshotHttpDownload
import rpcnode.toolkit.agent.infrastructure.http.SnapshotStreamExtract
import rpcnode.toolkit.agent.infrastructure.proc.SystemdTransientDownload
import rpcnode.toolkit.chains.base.infrastructure.http.BaseOfficialSnapshotRunner
import rpcnode.toolkit.chains.base.infrastructure.http.BaseSnapshotResolver
import rpcnode.toolkit.chains.bsc.infrastructure.http.BscOfficialSnapshotRunner
import rpcnode.toolkit.chains.bsc.infrastructure.http.BscSnapshotResolver
import rpcnode.toolkit.chains.sui.infrastructure.http.SuiFormalSnapshotRunner
import rpcnode.toolkit.chains.sui.infrastructure.http.SuiSnapshotResolver
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope

private val ARCHIVE_NAME = SnapshotStreamExtract.ARCHIVE_NAME

sealed interface StartSnapshotDownloadResult
{
    data object Started : StartSnapshotDownloadResult
    data object AlreadyRunning : StartSnapshotDownloadResult
}

sealed interface StopSnapshotDownloadResult
{
    data object Stopped : StopSnapshotDownloadResult
    data object NotRunning : StopSnapshotDownloadResult
}

/**
 * Downloads archive to `{destDir}/.toolkit/snapshot-archive.tgz` with infinite resume retries,
 * then extracts. Prefers aria2 multi-connection in a detached systemd unit so agent restarts
 * (watch / jar swap) do not kill the transfer; partial file is kept either way.
 * Only [stop] abandons progress (optional wipe).
 */
class StartSnapshotDownloadUseCase(
    private val store: SnapshotJobStore,
    @Suppress("unused") private val downloadRoot: Path,
    private val streamExtract: SnapshotStreamExtract = SnapshotStreamExtract(),
    private val scope: CoroutineScope,
    private val bscOfficial: BscOfficialSnapshotRunner = BscOfficialSnapshotRunner(),
    private val baseOfficial: BaseOfficialSnapshotRunner = BaseOfficialSnapshotRunner(),
    private val suiFormal: SuiFormalSnapshotRunner = SuiFormalSnapshotRunner(),
)
{
    private val log = LoggerFactory.getLogger(StartSnapshotDownloadUseCase::class.java)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val downloadUnits = ConcurrentHashMap<String, String>()

    /**
     * After agent boot: clear zombie `running` flags and continue unfinished downloads.
     */
    fun recoverInterrupted()
    {
        for (job in store.list())
        {
            if (job.ready || job.phase == "complete" || job.phase == "aborted")
            {
                if (job.running)
                {
                    store.write(job.copy(running = false))
                }
                continue
            }
            if (job.url.isBlank() || job.destDir.isBlank())
            {
                continue
            }
            val live = jobs[job.jobId]?.isActive == true
            if (live)
            {
                continue
            }
            if (job.running || job.failed || job.phase == "download" || job.phase == "extract" || job.phase == "starting")
            {
                log.info("recovering interrupted snapshot {}", job.jobId)
                store.write(job.copy(running = false, failed = false, error = ""))
                invoke(
                    jobId = job.jobId,
                    url = job.url,
                    destDir = job.destDir,
                    streamUnpack = job.streamUnpack,
                    sizeBytes = job.sizeBytes,
                )
            }
        }
    }

    operator fun invoke(
        jobId: String,
        url: String,
        destDir: String,
        streamUnpack: Boolean,
        sizeBytes: Long?,
    ): StartSnapshotDownloadResult
    {
        val id = jobId.trim()
        if (id.isBlank() || url.isBlank() || destDir.isBlank())
        {
            error("job_id, url and dest_dir are required")
        }
        if (jobs[id]?.isActive == true)
        {
            return StartSnapshotDownloadResult.AlreadyRunning
        }
        // Stale running=true from a previous agent process — resume instead of blocking.
        val prev = store.read(id)
        if (prev?.running == true)
        {
            store.write(prev.copy(running = false))
        }
        val archive = Path.of(destDir.trim(), ".toolkit", ARCHIVE_NAME)
        val already = if (Files.isRegularFile(archive)) Files.size(archive) else 0L
        val baseOfficialUrl = BaseSnapshotResolver.isBaseDownloadUrl(url.trim())
        val bscOfficialUrl = BscSnapshotResolver.isOfficialUrl(url.trim())
        val suiFormalUrl = SuiSnapshotResolver.isOfficialUrl(url.trim())
        val startLines = when
        {
            baseOfficialUrl ->
                listOf(
                    "Starting Base V2 snapshot download",
                    "url=$url",
                    "dest=$destDir",
                    "via=base-reth-node download (not curl/.tgz)",
                )
            bscOfficialUrl ->
                listOf(
                    "Starting BSC official snapshot download",
                    "url=$url",
                    "dest=$destDir",
                )
            suiFormalUrl ->
                listOf(
                    "Starting Sui formal snapshot download",
                    "url=$url",
                    "dest=$destDir",
                    "via=sui-tool download-formal-snapshot (not curl/.tgz)",
                )
            else ->
                buildList {
                    add(
                        if (already > 0)
                        {
                            "Resuming snapshot download from ${formatBytes(already)}"
                        }
                        else
                        {
                            "Starting snapshot download"
                        },
                    )
                    add("url=$url")
                    add("dest=$destDir")
                    sizeBytes?.takeIf { it > 0 }?.let { add("size=${formatBytes(it)}") }
                    add("partial file: .toolkit/$ARCHIVE_NAME (aria2/curl; detached systemd unit)")
                }
        }
        val job = withLog(
            SnapshotJob(
                jobId = id,
                url = url.trim(),
                destDir = destDir.trim(),
                streamUnpack = streamUnpack,
                sizeBytes = sizeBytes,
                pct = prev?.pct ?: 0.0,
                phase = "starting",
                detail = when
                {
                    baseOfficialUrl -> "Preparing Base V2 snapshot (base-reth-node)…"
                    bscOfficialUrl -> "Preparing BSC official snapshot…"
                    suiFormalUrl -> "Preparing Sui formal snapshot (sui-tool)…"
                    already > 0 -> "Resuming download from ${formatBytes(already)}…"
                    else -> "Preparing download…"
                },
                running = true,
                failed = false,
                error = "",
                ready = false,
                logTail = prev?.logTail.orEmpty(),
            ),
            *startLines.toTypedArray(),
        )
        store.write(job)
        val launched = scope.launch {
            try
            {
                runDownload(job)
            }
            finally
            {
                jobs.remove(id)
                processes.remove(id)
                downloadUnits.remove(id)
            }
        }
        jobs[id] = launched
        return StartSnapshotDownloadResult.Started
    }

    fun stop(jobId: String, wipeDest: Boolean = true): StopSnapshotDownloadResult
    {
        val id = jobId.trim()
        if (id.isBlank())
        {
            return StopSnapshotDownloadResult.NotRunning
        }
        val existing = store.read(id)
        val running = store.isRunning(id) || jobs[id]?.isActive == true
        // Mark aborted first so recoverInterrupted / UI cannot race a restart over the wipe.
        if (existing != null)
        {
            store.write(
                withLog(
                    existing.copy(
                        pct = 0.0,
                        phase = "aborted",
                        detail = "Stopped by operator — stopping downloader…",
                        ready = false,
                        failed = false,
                        error = "",
                        running = false,
                    ),
                    "Stop requested — killing downloader then wiping dest",
                ),
            )
        }
        processes.remove(id)?.destroyForcibly()
        jobs.remove(id)?.cancel()
        if (existing != null && SuiSnapshotResolver.isOfficialUrl(existing.url))
        {
            suiFormal.stopTool()
        }
        val unit = downloadUnits.remove(id)
            ?: SnapshotHttpDownload.unitNameForLabel("snapshot-$id")
        val dest = existing?.destDir?.trim().orEmpty()
        runCatching { SystemdTransientDownload.stop(unit, destHint = dest.ifBlank { null }) }
        if (wipeDest && dest.isNotBlank())
        {
            wipeDirectoryContents(Path.of(dest))
            // Second pass: orphans may recreate .toolkit between kill and first wipe.
            runCatching { SystemdTransientDownload.stop(unit, destHint = dest) }
            wipeDirectoryContents(Path.of(dest))
        }
        if (existing != null)
        {
            store.write(
                withLog(
                    (store.read(id) ?: existing).copy(
                        pct = 0.0,
                        phase = "aborted",
                        detail = "Stopped by operator — files removed",
                        ready = false,
                        failed = false,
                        error = "",
                        running = false,
                    ),
                    "Stopped by operator — destination wiped",
                ),
            )
        }
        return if (running || existing != null)
        {
            StopSnapshotDownloadResult.Stopped
        }
        else
        {
            StopSnapshotDownloadResult.NotRunning
        }
    }

    private fun runDownload(initial: SnapshotJob)
    {
        if (BscSnapshotResolver.isOfficialUrl(initial.url))
        {
            runBscOfficial(initial)
            return
        }
        if (BaseSnapshotResolver.isBaseDownloadUrl(initial.url))
        {
            runBaseOfficial(initial)
            return
        }
        if (SuiSnapshotResolver.isOfficialUrl(initial.url))
        {
            runSuiFormal(initial)
            return
        }
        val id = initial.jobId
        try
        {
            val dest = Path.of(initial.destDir)
            update(
                withLog(
                    initial.copy(
                        phase = "download",
                        detail = "Downloading archive (resumable) into ${initial.destDir}/.toolkit…",
                    ),
                    "Downloading archive (aria2 multi-conn or curl; detached systemd unit when available)",
                    "dest=${initial.destDir}",
                    "partial kept under .toolkit/$ARCHIVE_NAME — survives agent restart",
                ),
            )
            var lastLoggedDetail = ""
            var currentPhase = "download"
            streamExtract.fetchAndExtract(
                label = "snapshot-$id",
                url = initial.url,
                destDir = dest,
                expectedBytes = initial.sizeBytes,
                onProcess = { proc -> processes[id] = proc },
                onUnit = { unit -> downloadUnits[id] = unit },
                isAborted = { jobs[id]?.isActive == false },
                onRetry = { attempt, already, reason ->
                    val line = "Resume after disconnect (#$attempt): ${formatBytes(already)} kept — $reason"
                    val cur = store.read(id) ?: initial
                    update(withLog(cur.copy(phase = "download", detail = line, running = true), line))
                },
                onPhase = { phase ->
                    currentPhase = phase
                    if (phase == "extract")
                    {
                        val cur = store.read(id) ?: initial
                        update(
                            withLog(
                                cur.copy(
                                    phase = "extract",
                                    detail = "Extracting archive into ${initial.destDir}…",
                                    running = true,
                                ),
                                "Download complete — extracting with tar",
                            ),
                        )
                    }
                },
            ) { copied, total ->
                if (jobs[id]?.isActive == false)
                {
                    error("snapshot aborted")
                }
                val pct = when
                {
                    total != null && total > 0 -> (copied.toDouble() / total.toDouble()) * 100.0
                    else -> null
                }
                val detail = if (currentPhase == "extract")
                {
                    "Extracting… → ${initial.destDir}"
                }
                else
                {
                    progressDetail(copied, total) + " → ${initial.destDir}"
                }
                val base = (store.read(id) ?: initial).copy(
                    pct = pct ?: 0.0,
                    phase = currentPhase,
                    detail = detail,
                    running = true,
                )
                if (detail != lastLoggedDetail)
                {
                    lastLoggedDetail = detail
                    update(withLog(base, detail))
                }
                else
                {
                    update(base)
                }
            }
            if (jobs[id]?.isActive == false)
            {
                return
            }
            update(
                withLog(
                    (store.read(id) ?: initial).copy(
                        pct = 100.0,
                        phase = "complete",
                        detail = "Snapshot ready in ${initial.destDir}",
                        ready = true,
                        running = false,
                    ),
                    "Extract finished",
                    "Snapshot ready in ${initial.destDir}",
                ),
            )
        }
        catch (e: Throwable)
        {
            failDownload(id, initial, e)
        }
    }

    private fun runBscOfficial(initial: SnapshotJob)
    {
        val id = initial.jobId
        try
        {
            val ref = BscSnapshotResolver.parse(initial.url)
                ?: error("invalid bsc-official URL: ${initial.url}")
            val paths = bscOfficial.layout(
                dataDir = Path.of(initial.destDir),
                snapDir = ref.snapDir?.let { Path.of(it) },
                env = ref.env,
            )
            val script = bscOfficial.writeHealScript(paths, ref.env, ref.flavor)
            update(
                withLog(
                    initial.copy(
                        phase = "download",
                        detail = "Official BSC snapshot · ${ref.flavor} · resolving latest…",
                        running = true,
                    ),
                    "Starting official bnb-chain/bsc-snapshots (${ref.env} / ${ref.flavor})",
                    "data=${paths.data}",
                    "snap=${paths.snap}",
                    "script=$script",
                ),
            )
            if (bscOfficial.markerReady(paths.marker) &&
                Files.isDirectory(paths.data.resolve("geth").resolve("chaindata"))
            )
            {
                update(
                    withLog(
                        (store.read(id) ?: initial).copy(
                            pct = 100.0,
                            phase = "complete",
                            detail = "BSC official snapshot already ready",
                            ready = true,
                            running = false,
                        ),
                        "Marker present — snapshot ready",
                    ),
                )
                return
            }
            val proc = bscOfficial.startProcess(script)
            processes[id] = proc
            // Drain stdout so the heal script cannot block on a full pipe.
            val drain = Thread {
                try
                {
                    proc.inputStream.bufferedReader().use { it.readText() }
                }
                catch (_: Exception)
                {
                }
            }.also { it.isDaemon = true; it.start() }
            while (proc.isAlive)
            {
                if (jobs[id]?.isActive == false)
                {
                    proc.destroyForcibly()
                    error("snapshot aborted")
                }
                val state = bscOfficial.readState(paths.state)
                if (state != null)
                {
                    val cur = store.read(id) ?: initial
                    update(
                        cur.copy(
                            pct = state.pct ?: cur.pct,
                            phase = state.phase.ifBlank { "download" },
                            detail = state.detail.ifBlank { cur.detail },
                            error = state.error,
                            failed = state.phase.equals("error", ignoreCase = true),
                            running = true,
                        ),
                    )
                }
                Thread.sleep(2_000)
            }
            drain.join(5_000)
            val code = proc.exitValue()
            if (jobs[id]?.isActive == false)
            {
                return
            }
            if (bscOfficial.markerReady(paths.marker) && code == 0)
            {
                update(
                    withLog(
                        (store.read(id) ?: initial).copy(
                            pct = 100.0,
                            phase = "complete",
                            detail = "BSC official snapshot ready in ${paths.data}",
                            ready = true,
                            failed = false,
                            error = "",
                            running = false,
                        ),
                        "Official snapshot DONE",
                    ),
                )
                return
            }
            val state = bscOfficial.readState(paths.state)
            val msg = state?.error?.takeIf { it.isNotBlank() }
                ?: "official snapshot failed (exit=$code)"
            error(msg)
        }
        catch (e: Throwable)
        {
            failDownload(id, initial, e)
        }
    }

    private fun runBaseOfficial(initial: SnapshotJob)
    {
        val id = initial.jobId
        try
        {
            val ref = BaseSnapshotResolver.parse(initial.url)
                ?: error("invalid base snapshot URL: ${initial.url}")
            val manifestUrl = BaseSnapshotResolver.manifestUrlForDownload(initial.url)
            val paths = baseOfficial.layout(
                dataDir = Path.of(initial.destDir),
                env = ref.env,
            )
            val rethBin = baseOfficial.ensureRethBin(ref.env, Path.of(initial.destDir))
                ?: error(
                    "base-reth-node missing under ${initial.destDir} and /opt/base/${ref.env}/bin — " +
                        "run Sync clients for this Base node (GitHub base/base) before Snapshot",
                )
            val script = baseOfficial.writeHealScript(
                paths = paths,
                env = ref.env,
                flavor = ref.flavor,
                rethBin = rethBin,
                manifestUrl = manifestUrl,
            )
            val via = if (manifestUrl != null) "CDN --manifest-url" else "official discovery"
            update(
                withLog(
                    initial.copy(
                        phase = "download",
                        detail = "Base V2 snapshot · ${ref.flavor} · $via…",
                        running = true,
                    ),
                    "Starting Base V2 snapshot (${ref.env} / ${ref.flavor}) via $via",
                    "data=${paths.data}",
                    "script=$script",
                    "log=${paths.logFile}",
                ),
            )
            if (baseOfficial.markerReady(paths.marker) && baseOfficial.rethV2Present(paths.data))
            {
                update(
                    withLog(
                        (store.read(id) ?: initial).copy(
                            pct = 100.0,
                            phase = "complete",
                            detail = "Base V2 snapshot already ready",
                            ready = true,
                            running = false,
                        ),
                        "Marker present — snapshot ready",
                    ),
                )
                return
            }
            val proc = baseOfficial.startProcess(script)
            processes[id] = proc
            val drain = Thread {
                try
                {
                    proc.inputStream.bufferedReader().use { reader ->
                        while (true)
                        {
                            if (reader.readLine() == null)
                            {
                                break
                            }
                        }
                    }
                }
                catch (_: Exception)
                {
                }
            }.also { it.isDaemon = true; it.start() }
            while (proc.isAlive)
            {
                if (jobs[id]?.isActive == false)
                {
                    proc.destroyForcibly()
                    error("snapshot aborted")
                }
                val state = baseOfficial.readLiveProgress(paths, ref.flavor)
                if (state != null)
                {
                    val cur = store.read(id) ?: initial
                    update(
                        cur.copy(
                            pct = state.pct ?: cur.pct,
                            phase = state.phase.ifBlank { "download" },
                            detail = state.detail.ifBlank { cur.detail },
                            error = state.error,
                            failed = state.phase.equals("error", ignoreCase = true),
                            running = true,
                        ),
                    )
                }
                Thread.sleep(2_000)
            }
            drain.join(5_000)
            val code = proc.exitValue()
            if (jobs[id]?.isActive == false)
            {
                return
            }
            if (baseOfficial.markerReady(paths.marker) && code == 0)
            {
                update(
                    withLog(
                        (store.read(id) ?: initial).copy(
                            pct = 100.0,
                            phase = "complete",
                            detail = "Base V2 snapshot ready in ${paths.data}",
                            ready = true,
                            failed = false,
                            error = "",
                            running = false,
                        ),
                        "Official snapshot DONE",
                    ),
                )
                return
            }
            val state = baseOfficial.readState(paths.state)
            val msg = state?.error?.takeIf { it.isNotBlank() }
                ?: "official snapshot failed (exit=$code)"
            error(msg)
        }
        catch (e: Throwable)
        {
            failDownload(id, initial, e)
        }
    }

    private fun runSuiFormal(initial: SnapshotJob)
    {
        val id = initial.jobId
        try
        {
            val ref = SuiSnapshotResolver.parse(initial.url)
                ?: error("invalid formal-r2 URL: ${initial.url}")
            val dest = Path.of(initial.destDir)
            val paths = suiFormal.layout(dbDir = dest, nodeDir = dest)
            val tool = suiFormal.ensureTool(paths.nodeDir)
                ?: paths.nodeDir.parent?.let { suiFormal.ensureTool(it) }
                ?: error(
                    "sui-tool missing under ${paths.nodeDir}/bin — " +
                        "run Sync clients for this Sui node (MystenLabs/sui) before Snapshot",
                )
            if (!Files.isRegularFile(paths.genesis) || Files.size(paths.genesis) <= 0)
            {
                error(
                    "genesis.blob missing at ${paths.genesis} — " +
                        "sync Sui clients (MystenLabs/sui-genesis) before Snapshot",
                )
            }
            val script = suiFormal.writeHealScript(paths, ref.env, tool)
            update(
                withLog(
                    initial.copy(
                        phase = "download",
                        detail = "Sui formal snapshot · ${ref.env} · sui-tool…",
                        running = true,
                    ),
                    "Starting Sui formal snapshot (${ref.env})",
                    "db=${paths.db}",
                    "script=$script",
                    "log=${paths.logFile}",
                ),
            )
            if (suiFormal.markerReady(paths.marker))
            {
                update(
                    withLog(
                        (store.read(id) ?: initial).copy(
                            pct = 100.0,
                            phase = "complete",
                            detail = "Sui formal snapshot already ready",
                            ready = true,
                            running = false,
                        ),
                        "Marker present — snapshot ready",
                    ),
                )
                return
            }
            val proc = suiFormal.startProcess(script)
            processes[id] = proc
            val drain = Thread {
                try
                {
                    proc.inputStream.bufferedReader().use { reader ->
                        while (true)
                        {
                            if (reader.readLine() == null)
                            {
                                break
                            }
                        }
                    }
                }
                catch (_: Exception)
                {
                }
            }.also { it.isDaemon = true; it.start() }
            while (proc.isAlive)
            {
                if (jobs[id]?.isActive == false)
                {
                    proc.destroyForcibly()
                    suiFormal.stopTool()
                    error("snapshot aborted")
                }
                val state = suiFormal.readLiveProgress(paths)
                if (state != null)
                {
                    val cur = store.read(id) ?: initial
                    update(
                        cur.copy(
                            pct = state.pct ?: cur.pct,
                            phase = state.phase.ifBlank { "download" },
                            detail = state.detail.ifBlank { cur.detail },
                            error = state.error,
                            failed = state.phase.equals("error", ignoreCase = true),
                            running = true,
                        ),
                    )
                }
                Thread.sleep(2_000)
            }
            drain.join(5_000)
            val code = proc.exitValue()
            if (jobs[id]?.isActive == false)
            {
                return
            }
            if (suiFormal.markerReady(paths.marker) && code == 0)
            {
                update(
                    withLog(
                        (store.read(id) ?: initial).copy(
                            pct = 100.0,
                            phase = "complete",
                            detail = "Sui formal snapshot ready in ${paths.db}",
                            ready = true,
                            failed = false,
                            error = "",
                            running = false,
                        ),
                        "Formal snapshot DONE",
                    ),
                )
                return
            }
            val state = suiFormal.readState(paths.state)
            val msg = state?.error?.takeIf { it.isNotBlank() }
                ?: "formal snapshot failed (exit=$code)"
            error(msg)
        }
        catch (e: Throwable)
        {
            failDownload(id, initial, e)
        }
    }

    private fun failDownload(id: String, initial: SnapshotJob, e: Throwable)
    {
        if (e is kotlin.coroutines.cancellation.CancellationException)
        {
            log.info("snapshot {} cancelled", id)
            return
        }
        if (jobs[id]?.isActive == false || e.message?.contains("aborted") == true)
        {
            log.info("snapshot {} aborted", id)
            return
        }
        val msg = formatFailure(e, initial.destDir)
        log.warn("snapshot {} failed: {}", id, msg)
        update(
            withLog(
                (store.read(id) ?: initial).copy(
                    phase = "failed",
                    detail = msg,
                    failed = true,
                    error = msg,
                    running = false,
                ),
                "FAILED: $msg",
            ),
        )
    }

    private fun update(job: SnapshotJob)
    {
        store.write(job)
    }

    private fun withLog(job: SnapshotJob, vararg lines: String?): SnapshotJob
    {
        val stamp = LOG_TIME.format(Instant.now())
        val added = lines.mapNotNull { it?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .map { "$stamp  $it" }
        if (added.isEmpty())
        {
            return job
        }
        return job.copy(logTail = (job.logTail + added).takeLast(LOG_TAIL_MAX))
    }

    private fun formatFailure(e: Throwable, destDir: String): String
    {
        val raw = e.message?.trim().orEmpty()
        if (raw.startsWith("cannot create dest_dir") ||
            raw.startsWith("dest_dir ") ||
            raw.startsWith("download failed") ||
            raw.startsWith("tar extract")
        )
        {
            return raw
        }
        val path = runCatching { Path.of(destDir) }.getOrNull()
        if (path != null && (e is java.nio.file.FileSystemException || raw == destDir.trim() || raw == path.toString()))
        {
            return SnapshotDestDirPrep.formatThrowable("snapshot dest_dir", path, e)
        }
        return raw.ifBlank { e.javaClass.simpleName }
    }

    private fun wipeDirectoryContents(dir: Path)
    {
        if (!Files.isDirectory(dir))
        {
            return
        }
        try
        {
            Files.walk(dir).use { stream ->
                stream
                    .sorted(Comparator.reverseOrder())
                    .filter { it != dir }
                    .forEach { Files.deleteIfExists(it) }
            }
        }
        catch (e: Exception)
        {
            log.warn("wipe {}: {}", dir, e.message)
        }
    }

    private fun progressDetail(copied: Long, total: Long?): String
    {
        val got = formatBytes(copied)
        val all = total?.let { formatBytes(it) }
        return if (all != null)
        {
            "Downloaded $got / $all"
        }
        else
        {
            "Downloaded $got"
        }
    }

    private fun formatBytes(bytes: Long): String = SnapshotHttpDownload.formatBytes(bytes)

    companion object
    {
        private const val LOG_TAIL_MAX = 200
        private val LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC)
    }
}

class GetSnapshotProgressUseCase(
    private val store: SnapshotJobStore,
)
{
    operator fun invoke(jobId: String): SnapshotJob?
    {
        val id = jobId.trim()
        if (id.isBlank())
        {
            return null
        }
        return store.read(id)
    }
}
