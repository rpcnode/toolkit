package rpcnode.toolkit.agent.application.snapshot

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.domain.model.SnapshotJob
import rpcnode.toolkit.agent.infrastructure.http.BscStreamSnapshot
import rpcnode.toolkit.agent.infrastructure.http.SnapshotDestDirPrep
import rpcnode.toolkit.agent.infrastructure.http.SnapshotHttpDownload
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport
import rpcnode.toolkit.nodes.infrastructure.host.NodeProxyEnv
import rpcnode.toolkit.agent.infrastructure.http.SnapshotStreamExtract
import rpcnode.toolkit.agent.infrastructure.http.SubstrateFilesSnapshot
import rpcnode.toolkit.agent.infrastructure.proc.SystemdTransientDownload
import rpcnode.toolkit.chains.arc.infrastructure.http.ArcSnapshotResolver
import rpcnode.toolkit.chains.arc.infrastructure.http.ArcSnapshotRunner
import rpcnode.toolkit.chains.base.infrastructure.http.BaseOfficialSnapshotRunner
import rpcnode.toolkit.chains.base.infrastructure.http.BaseSnapshotResolver
import rpcnode.toolkit.chains.bsc.infrastructure.http.BscOfficialSnapshotRunner
import rpcnode.toolkit.chains.bsc.infrastructure.http.BscSnapshotResolver
import rpcnode.toolkit.chains.cardano.infrastructure.http.CardanoMithrilSnapshotRunner
import rpcnode.toolkit.chains.cardano.infrastructure.http.CardanoSnapshotResolver
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotSentinel
import rpcnode.toolkit.chains.evmfamily.infrastructure.http.EvmSnapshotRunner
import rpcnode.toolkit.chains.substrate.infrastructure.http.SubstrateSnapshotResolver
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
    private val bscStream: BscStreamSnapshot = BscStreamSnapshot(),
    private val baseOfficial: BaseOfficialSnapshotRunner = BaseOfficialSnapshotRunner(),
    private val suiFormal: SuiFormalSnapshotRunner = SuiFormalSnapshotRunner(),
    private val cardanoMithril: CardanoMithrilSnapshotRunner = CardanoMithrilSnapshotRunner(),
    private val arcSnapshots: ArcSnapshotRunner = ArcSnapshotRunner(),
    private val substrateFiles: SubstrateFilesSnapshot = SubstrateFilesSnapshot(),
    private val evmSnapshots: EvmSnapshotRunner = EvmSnapshotRunner(),
)
{
    private val log = LoggerFactory.getLogger(StartSnapshotDownloadUseCase::class.java)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val processes = ConcurrentHashMap<String, Process>()
    private val downloadUnits = ConcurrentHashMap<String, String>()

    /** In-process downloads (Substrate files) have no child process to kill: [stop] flips this flag instead. */
    private val inProcessAbort = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()

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
                    proxy = job.proxy,
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
        proxy: String = "",
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
        val cardanoMithrilUrl = CardanoSnapshotResolver.isOfficialUrl(url.trim())
        val arcSnapshotUrl = ArcSnapshotResolver.isOfficialUrl(url.trim())
        val substrateFilesUrl = SubstrateSnapshotResolver.isOfficialUrl(url.trim())
        val evmSnapshotUrl = EvmSnapshotSentinel.isSentinel(url.trim())
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
            cardanoMithrilUrl ->
                listOf(
                    "Starting Cardano Mithril snapshot restore",
                    "url=$url",
                    "dest=$destDir",
                    "via=mithril-client cardano-db download (certificate chain verified; no resume)",
                )
            arcSnapshotUrl ->
                listOf(
                    "Starting Arc snapshot restore",
                    "url=$url",
                    "dest=$destDir",
                    "via=arc-snapshots download (execution + consensus layers)",
                )
            evmSnapshotUrl ->
                listOf(
                    "Starting scripted snapshot restore",
                    "url=${url.substringBefore('?')}",
                    "dest=$destDir",
                    "via=client CLI download / genesis import / archive pair (see .toolkit/evm-snapshot.sh)",
                )
            substrateFilesUrl ->
                listOf(
                    "Starting Substrate database snapshot restore",
                    "url=$url",
                    "dest=$destDir",
                    "via=files.txt manifest, ${SubstrateFilesSnapshot.PARALLEL} parallel resumable GETs (no archive, no rclone)",
                )
            else ->
                buildList {
                    add(
                        if (already > 0 && !streamUnpack)
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
                    if (streamUnpack)
                    {
                        add("mode=stream unpack (HTTP → tar, no staging archive; resumes after disconnects)")
                    }
                    else
                    {
                        add("partial file: .toolkit/$ARCHIVE_NAME (aria2/curl; detached systemd unit)")
                    }
                }
        }
        val job = withLog(
            SnapshotJob(
                jobId = id,
                url = url.trim(),
                destDir = destDir.trim(),
                streamUnpack = streamUnpack,
                sizeBytes = sizeBytes,
                proxy = proxy.trim(),
                pct = prev?.pct ?: 0.0,
                phase = "starting",
                detail = when
                {
                    baseOfficialUrl -> "Preparing Base V2 snapshot (base-reth-node)…"
                    bscOfficialUrl -> "Preparing BSC official snapshot…"
                    suiFormalUrl -> "Preparing Sui formal snapshot (sui-tool)…"
                    cardanoMithrilUrl -> "Preparing Cardano Mithril snapshot (mithril-client)…"
                    arcSnapshotUrl -> "Preparing Arc snapshot (arc-snapshots)…"
                    substrateFilesUrl -> "Preparing Substrate database snapshot…"
                    evmSnapshotUrl -> "Preparing scripted snapshot restore…"
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
            proxy.trim().takeIf { it.isNotEmpty() }?.let { "proxy=${NodeProxyEnv.mask(it)}" },
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
        inProcessAbort[id]?.set(true)
        processes.remove(id)?.let { killProcessTree(it) }
        jobs.remove(id)?.cancel()
        if (existing != null && SuiSnapshotResolver.isOfficialUrl(existing.url))
        {
            suiFormal.stopTool()
        }
        if (existing != null && CardanoSnapshotResolver.isOfficialUrl(existing.url))
        {
            cardanoMithril.stopTool()
        }
        if (existing != null && ArcSnapshotResolver.isOfficialUrl(existing.url))
        {
            arcSnapshots.stopTool()
        }
        if (existing != null && EvmSnapshotSentinel.isSentinel(existing.url))
        {
            evmSnapshots.stopTool()
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

    /**
     * Stops every running download whose destination lies under one of [dirs] (used before a node is removed:
     * files must not be deleted under a live aria2/tar). Keeps what was downloaded; the caller deletes it.
     * Returns how many jobs were stopped.
     */
    fun stopJobsUnder(dirs: List<Path>): Int
    {
        val roots = dirs.map { it.toAbsolutePath().normalize() }
        var stopped = 0
        for (job in store.list())
        {
            val running = store.isRunning(job.jobId) || jobs[job.jobId]?.isActive == true
            if (!running)
            {
                continue
            }
            val dest = runCatching { Path.of(job.destDir.trim()).toAbsolutePath().normalize() }.getOrNull() ?: continue
            if (roots.any { dest.startsWith(it) || it.startsWith(dest) })
            {
                stop(job.jobId, wipeDest = false)
                stopped++
            }
        }
        return stopped
    }

    /** The heal scripts run aria2/tar/lz4 as grandchildren: killing only the script orphans them. */
    private fun killProcessTree(proc: Process)
    {
        runCatching { proc.toHandle().descendants().forEach { it.destroyForcibly() } }
        proc.destroyForcibly()
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
        if (CardanoSnapshotResolver.isOfficialUrl(initial.url))
        {
            runCardanoMithril(initial)
            return
        }
        if (ArcSnapshotResolver.isOfficialUrl(initial.url))
        {
            runArcSnapshot(initial)
            return
        }
        if (EvmSnapshotSentinel.isSentinel(initial.url))
        {
            runEvmSnapshot(initial)
            return
        }
        if (SubstrateSnapshotResolver.isOfficialUrl(initial.url))
        {
            runSubstrateFiles(initial)
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
                        detail = if (initial.streamUnpack)
                        {
                            "Streaming download + extract into ${initial.destDir}…"
                        }
                        else
                        {
                            "Downloading archive (resumable) into ${initial.destDir}/.toolkit…"
                        },
                    ),
                    if (initial.streamUnpack)
                    {
                        "Stream unpack: HTTP → tar -xzf - (no .tgz on disk)"
                    }
                    else
                    {
                        "Downloading archive (aria2 multi-conn or curl; detached systemd unit when available)"
                    },
                    "dest=${initial.destDir}",
                    if (initial.streamUnpack)
                    {
                        "reconnects with HTTP Range after a network drop — lost only if the agent restarts"
                    }
                    else
                    {
                        "partial kept under .toolkit/$ARCHIVE_NAME — survives agent restart"
                    },
                ),
            )
            var lastLoggedDetail = ""
            var currentPhase = "download"
            streamExtract.fetchAndExtract(
                label = "snapshot-$id",
                url = initial.url,
                destDir = dest,
                expectedBytes = initial.sizeBytes,
                streamUnpack = initial.streamUnpack,
                proxy = initial.proxy,
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
                val detail = when
                {
                    initial.streamUnpack ->
                        "Streaming… ${progressDetail(copied, total)} → ${initial.destDir}"
                    currentPhase == "extract" ->
                        "Extracting… ${extractDetail(copied, total)} → ${initial.destDir}"
                    else ->
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
            if (ref.stream)
            {
                runBscStream(initial, ref)
                return
            }
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
            val proc = bscOfficial.startProcess(script, NodeProxyEnv.vars(initial.proxy))
            processes[id] = proc
            // Drain stdout so the heal script cannot block on a full pipe — and keep the last lines: when the
            // script dies early (apt-get for aria2/lz4, network) that output is the only explanation.
            val scriptTail = java.util.concurrent.ConcurrentLinkedDeque<String>()
            val drain = Thread {
                try
                {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { raw ->
                            val line = raw.trim()
                            if (line.isNotEmpty())
                            {
                                scriptTail.addLast(line.take(300))
                                while (scriptTail.size > 40) scriptTail.pollFirst()
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
                else
                {
                    // The script has not written its state file yet (installing aria2/lz4, resolving the latest
                    // snapshot): show what it is doing instead of a silent empty bar.
                    scriptTail.peekLast()?.let { last ->
                        val cur = store.read(id) ?: initial
                        update(cur.copy(detail = "Official BSC snapshot · $last".take(200), running = true))
                    }
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
            val outputTail = scriptTail.toList().takeLast(15).joinToString("\n")
            val msg = state?.error?.takeIf { it.isNotBlank() }
                ?: ("official snapshot failed (exit=$code)" + if (outputTail.isNotEmpty()) ":\n$outputTail" else "")
            error(msg)
        }
        catch (e: Throwable)
        {
            failDownload(id, initial, e)
        }
    }

    /** [BscSnapshotResolver.OfficialRef.stream]: CSV parts piped into the datadir, no fetch-snapshot.sh. */
    private fun runBscStream(initial: SnapshotJob, ref: BscSnapshotResolver.OfficialRef)
    {
        val id = initial.jobId
        val paths = bscOfficial.layout(
            dataDir = Path.of(initial.destDir),
            snapDir = ref.snapDir?.let { Path.of(it) },
            env = ref.env,
        )
        if (bscOfficial.markerReady(paths.marker) &&
            Files.isDirectory(paths.data.resolve("geth").resolve("chaindata"))
        )
        {
            update(
                withLog(
                    initial.copy(
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
        update(
            withLog(
                initial.copy(
                    phase = "download",
                    detail = "BSC snapshot stream · ${ref.flavor} · resolving latest…",
                    running = true,
                ),
                "Starting official bnb-chain/bsc-snapshots as a stream (${ref.env} / ${ref.flavor})",
                "data=${paths.data}",
                "via=HTTP → tar -I lz4 (no archive on the snapshots disk)",
            ),
        )
        // geth must not hold the datadir while parts land in it.
        runCatching {
            ProcessBuilder("systemctl", "stop", "rpcnode-bsc-${ref.env}.service")
                .redirectErrorStream(true)
                .start()
                .waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
        }
        val plan = bscStream.resolvePlan(ref.env, ref.flavor)
        var done = bscStream.donePartsFor(paths.data, plan)
        if (done.isEmpty())
        {
            Files.deleteIfExists(paths.marker)
            bscStream.beginFresh(paths.data, plan)
        }
        val total = plan.totalBytes.takeIf { it > 0 }
        var doneBytes = plan.parts.filter { it.filename in done }.sumOf { it.sizeBytes ?: 0L }
        update(
            withLog(
                store.read(id) ?: initial,
                "Snapshot ${plan.csvName}: ${plan.parts.size} part(s), ${total?.let { formatBytes(it) } ?: "size unknown"}",
                *plan.parts.map { p ->
                    "  ${p.filename} ${p.sizeBytes?.let { formatBytes(it) } ?: ""}" + if (p.filename in done) " (done)" else ""
                }.toTypedArray(),
            ),
        )
        val aborted = { jobs[id]?.isActive == false }
        plan.parts.forEachIndexed { index, part ->
            if (part.filename in done)
            {
                return@forEachIndexed
            }
            val label = "${index + 1}/${plan.parts.size} ${part.filename}"
            update(withLog(store.read(id) ?: initial, "Streaming $label"))
            var lastWrite = 0L
            bscStream.extractPart(
                part = part,
                dataDir = paths.data,
                isAborted = aborted,
                onProcess = { processes[id] = it },
                onRetry = { attempt, already, reason ->
                    update(
                        withLog(
                            store.read(id) ?: initial,
                            "Retry #$attempt on ${part.filename} at ${formatBytes(already)}: $reason",
                        ),
                    )
                },
                onProgress = { copied, partTotal ->
                    val now = System.currentTimeMillis()
                    if (now - lastWrite >= 2_000)
                    {
                        lastWrite = now
                        val all = total ?: partTotal
                        val pct = all?.takeIf { it > 0 }?.let { ((doneBytes + copied) * 100.0 / it).coerceIn(0.0, 99.9) }
                        val cur = store.read(id) ?: initial
                        update(
                            cur.copy(
                                pct = pct ?: cur.pct,
                                phase = "download",
                                detail = "BSC snapshot stream · $label · ${formatBytes(copied)}" +
                                    (partTotal?.let { " / ${formatBytes(it)}" } ?: ""),
                                running = true,
                            ),
                        )
                    }
                },
            )
            if (aborted())
            {
                error("snapshot aborted")
            }
            bscStream.markPartDone(paths.data, part)
            done = done + part.filename
            doneBytes += part.sizeBytes ?: 0L
            update(withLog(store.read(id) ?: initial, "Part done: ${part.filename} (MD5 ok)"))
        }
        if (!Files.isDirectory(paths.data.resolve("geth").resolve("chaindata")))
        {
            error("stream finished but ${paths.data}/geth/chaindata is missing")
        }
        Files.writeString(paths.marker, Instant.now().toString() + "\n")
        bscStream.clearDoneRecord(paths.data)
        update(
            withLog(
                (store.read(id) ?: initial).copy(
                    pct = 100.0,
                    phase = "complete",
                    detail = "BSC official snapshot ready in ${paths.data} · ${plan.name}",
                    ready = true,
                    failed = false,
                    error = "",
                    running = false,
                ),
                "Stream snapshot DONE ${plan.csvName}",
            ),
        )
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
            val proc = baseOfficial.startProcess(script, NodeProxyEnv.vars(initial.proxy))
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
            val proc = suiFormal.startProcess(script, NodeProxyEnv.vars(initial.proxy))
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

    private fun runCardanoMithril(initial: SnapshotJob)
    {
        val id = initial.jobId
        try
        {
            val ref = CardanoSnapshotResolver.parse(initial.url)
                ?: error("invalid mithril URL: ${initial.url}")
            val dest = Path.of(initial.destDir)
            val paths = cardanoMithril.layout(dest)
            val client = cardanoMithril.ensureClient(paths.dest)
                ?: error(
                    "mithril-client missing under ${paths.dest}/bin — " +
                        "run Sync clients for this Cardano node (IntersectMBO/mithril) before Snapshot",
                )
            val script = cardanoMithril.writeScript(paths, ref.env, client)
            update(
                withLog(
                    initial.copy(
                        phase = "download",
                        detail = "Cardano Mithril snapshot · ${ref.env} · mithril-client…",
                        running = true,
                    ),
                    "Starting Cardano Mithril snapshot (${ref.env})",
                    "db=${paths.db}",
                    "script=$script",
                    "log=${paths.logFile}",
                ),
            )
            if (cardanoMithril.markerReady(paths.marker))
            {
                update(
                    withLog(
                        (store.read(id) ?: initial).copy(
                            pct = 100.0,
                            phase = "complete",
                            detail = "Cardano Mithril snapshot already ready",
                            ready = true,
                            running = false,
                        ),
                        "Marker present — snapshot ready",
                    ),
                )
                return
            }
            val proc = cardanoMithril.startProcess(script, NodeProxyEnv.vars(initial.proxy))
            processes[id] = proc
            val drain = Thread {
                try
                {
                    proc.inputStream.bufferedReader().use { reader ->
                        while (reader.readLine() != null)
                        {
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
                    cardanoMithril.stopTool()
                    error("snapshot aborted")
                }
                val state = cardanoMithril.readLiveProgress(paths)
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
            if (cardanoMithril.markerReady(paths.marker) && code == 0)
            {
                update(
                    withLog(
                        (store.read(id) ?: initial).copy(
                            pct = 100.0,
                            phase = "complete",
                            detail = "Cardano Mithril snapshot ready in ${paths.db}",
                            ready = true,
                            failed = false,
                            error = "",
                            running = false,
                        ),
                        "Mithril snapshot DONE",
                    ),
                )
                return
            }
            val state = cardanoMithril.readState(paths.state)
            val msg = state?.error?.takeIf { it.isNotBlank() }
                ?: "mithril snapshot failed (exit=$code)"
            error(msg)
        }
        catch (e: Throwable)
        {
            failDownload(id, initial, e)
        }
    }

    /**
     * Parity / IBP database snapshot: the files of a `files.txt` manifest are copied into the chain's database directory
     * ([SubstrateFilesSnapshot]). The node and its REST API are stopped first: they must not hold the database.
     */
    private fun runSubstrateFiles(initial: SnapshotJob)
    {
        val id = initial.jobId
        try
        {
            val ref = SubstrateSnapshotResolver.parse(initial.url)
                ?: error("invalid substrate-files URL: ${initial.url}")
            runCatching {
                for (unit in listOf(HostNodeLaunchSupport.unitName(ref.network, ref.env), "rpcnode-${ref.network}-${ref.env}-indexer.service"))
                {
                    ProcessBuilder("systemctl", "stop", unit).redirectErrorStream(true).start()
                        .waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            update(
                withLog(
                    initial.copy(phase = "download", detail = "Substrate snapshot · ${ref.chainDirId} · listing files…", running = true),
                    "Restoring ${ref.baseUrl}",
                    "database=${ref.db.flag} chain=${ref.chainDirId}",
                ),
            )
            var lastPctLogged = -10
            val abort = java.util.concurrent.atomic.AtomicBoolean(false).also { inProcessAbort[id] = it }
            try
            {
                substrateFiles.run(
                    ref = ref,
                    dest = Path.of(initial.destDir),
                    proxy = initial.proxy,
                    isAborted = { abort.get() || jobs[id]?.isActive == false },
                    onLog = { line -> update(withLog(store.read(id) ?: initial, line)) },
                    onProgress = { p ->
                        val cur = store.read(id) ?: initial
                        val detail = when (p.phase)
                        {
                            "sizes" -> "Substrate snapshot · reading file sizes…"
                            else -> "Substrate snapshot · ${p.filesDone}/${p.filesTotal} files · ${formatBytes(p.doneBytes)} / ${formatBytes(p.totalBytes)}"
                        }
                        val next = cur.copy(pct = p.pct.coerceAtMost(99.9), phase = "download", detail = detail, running = true)
                        val step = (p.pct / 10).toInt() * 10
                        if (step > lastPctLogged)
                        {
                            lastPctLogged = step
                            update(withLog(next, detail))
                        }
                        else
                        {
                            update(next)
                        }
                    },
                )
            }
            finally
            {
                inProcessAbort.remove(id)
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
                        detail = "Substrate snapshot ready in ${initial.destDir}",
                        ready = true,
                        failed = false,
                        error = "",
                        running = false,
                    ),
                    "Snapshot files DONE",
                ),
            )
        }
        catch (e: Throwable)
        {
            failDownload(id, initial, e)
        }
    }

    private fun runArcSnapshot(initial: SnapshotJob)
    {
        val id = initial.jobId
        try
        {
            val ref = ArcSnapshotResolver.parse(initial.url)
                ?: error("invalid arc-snapshots URL: ${initial.url}")
            val dest = Path.of(initial.destDir)
            val paths = arcSnapshots.layout(dest, ref.kind)
            val tool = arcSnapshots.ensureTool(paths.root)
                ?: error(
                    "arc-snapshots missing under ${paths.root}/bin — " +
                        "run Sync clients for this Arc node (circlefin/arc-node) before Snapshot",
                )
            val script = arcSnapshots.writeScript(paths, ref.env, ref.kind, tool)
            update(
                withLog(
                    initial.copy(
                        phase = "download",
                        detail = "Arc snapshot · ${ref.env} · ${ref.kind} · arc-snapshots…",
                        running = true,
                    ),
                    "Starting Arc snapshot restore (${ref.env}, ${ref.kind})",
                    "execution=${paths.execution}",
                    "consensus=${paths.consensus}",
                    "script=$script",
                    "log=${paths.logFile}",
                ),
            )
            if (arcSnapshots.markerReady(paths.marker))
            {
                update(
                    withLog(
                        (store.read(id) ?: initial).copy(
                            pct = 100.0,
                            phase = "complete",
                            detail = "Arc snapshot already restored",
                            ready = true,
                            running = false,
                        ),
                        "Marker present — snapshot ready",
                    ),
                )
                return
            }
            val proc = arcSnapshots.startProcess(script, NodeProxyEnv.vars(initial.proxy))
            processes[id] = proc
            val drain = Thread {
                try
                {
                    proc.inputStream.bufferedReader().use { reader ->
                        while (reader.readLine() != null)
                        {
                            // the script tees everything into its own log
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
                    arcSnapshots.stopTool()
                    error("snapshot aborted")
                }
                val state = arcSnapshots.readLiveProgress(paths)
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
            if (arcSnapshots.markerReady(paths.marker) && code == 0)
            {
                update(
                    withLog(
                        (store.read(id) ?: initial).copy(
                            pct = 100.0,
                            phase = "complete",
                            detail = "Arc snapshot restored (${ref.kind})",
                            ready = true,
                            failed = false,
                            error = "",
                            running = false,
                        ),
                        "Arc snapshot DONE",
                    ),
                )
                return
            }
            val tail = arcSnapshots.failureTail(paths.logFile)
            if (tail.isNotEmpty())
            {
                update(withLog(store.read(id) ?: initial, "arc-snapshots output (tail):", *tail.map { "  $it" }.toTypedArray()))
            }
            val msg = arcSnapshots.failureMessage(paths.state)
                ?: "arc-snapshots failed (exit=$code)"
            error(arcSnapshots.failureReason(tail)?.let { "$msg · $it" } ?: msg)
        }
        catch (e: Throwable)
        {
            failDownload(id, initial, e)
        }
    }

    /** EVM-family scripted restore (`evm-snapshot://`): the network's recipe writes the script, this runs it and mirrors its state. */
    private fun runEvmSnapshot(initial: SnapshotJob)
    {
        val id = initial.jobId
        try
        {
            val ref = EvmSnapshotSentinel.parse(initial.url)
                ?: error("invalid evm-snapshot URL: ${initial.url}")
            val profile = evmSnapshots.snapshotFor(ref)
                ?: error("no scripted snapshot for network ${ref.network}")
            val dest = Path.of(initial.destDir)
            val bins = when (val r = evmSnapshots.ensureBinaries(profile, ref.env, dest))
            {
                is rpcnode.toolkit.chains.evmfamily.infrastructure.EvmHostBinaries.Result.Failed -> error(r.detail)
                is rpcnode.toolkit.chains.evmfamily.infrastructure.EvmHostBinaries.Result.Ok -> r.bins
            }
            val paths = evmSnapshots.layout(dest, bins)
            val script = evmSnapshots.writeScript(profile, ref, paths)
                ?: error("${ref.network} has no scripted snapshot")
            update(
                withLog(
                    initial.copy(
                        phase = "download",
                        detail = "${ref.network} snapshot · ${ref.env} · ${ref.kind}…",
                        running = true,
                    ),
                    "Starting ${ref.network} snapshot restore (${ref.env}, ${ref.kind})",
                    "script=$script",
                    "log=${paths.logFile}",
                ),
            )
            if (evmSnapshots.markerReady(paths.marker))
            {
                update(
                    withLog(
                        (store.read(id) ?: initial).copy(
                            pct = 100.0,
                            phase = "complete",
                            detail = "Snapshot already restored",
                            ready = true,
                            running = false,
                        ),
                        "Marker present — snapshot ready",
                    ),
                )
                return
            }
            val proc = evmSnapshots.startProcess(script, NodeProxyEnv.vars(initial.proxy))
            processes[id] = proc
            val drain = Thread {
                try
                {
                    proc.inputStream.bufferedReader().use { reader ->
                        while (reader.readLine() != null)
                        {
                            // the script tees everything into its own log
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
                    evmSnapshots.stopTool()
                    error("snapshot aborted")
                }
                val state = evmSnapshots.readLiveProgress(paths)
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
            if (evmSnapshots.markerReady(paths.marker) && code == 0)
            {
                update(
                    withLog(
                        (store.read(id) ?: initial).copy(
                            pct = 100.0,
                            phase = "complete",
                            detail = "${ref.network} snapshot restored (${ref.kind})",
                            ready = true,
                            failed = false,
                            error = "",
                            running = false,
                        ),
                        "${ref.network} snapshot DONE",
                    ),
                )
                return
            }
            val tail = evmSnapshots.failureTail(paths.logFile)
            if (tail.isNotEmpty())
            {
                update(withLog(store.read(id) ?: initial, "snapshot script output (tail):", *tail.map { "  $it" }.toTypedArray()))
            }
            val msg = evmSnapshots.failureMessage(paths.state) ?: "snapshot script failed (exit=$code)"
            error(tail.lastOrNull { it.startsWith("Error", ignoreCase = true) }?.let { "$msg · $it" } ?: msg)
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
            Files.list(dir).use { children ->
                children.forEach { child ->
                    if (SnapshotStreamExtract.shouldPreserveAlongsideSnapshot(child))
                    {
                        return@forEach
                    }
                    if (Files.isDirectory(child))
                    {
                        Files.walk(child).use { stream ->
                            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                        }
                    }
                    else
                    {
                        Files.deleteIfExists(child)
                    }
                }
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

    /** Archive bytes fed to tar so far (compressed size — the unpacked size is unknown). */
    private fun extractDetail(copied: Long, total: Long?): String
    {
        val got = formatBytes(copied)
        if (total == null || total <= 0)
        {
            return "$got read"
        }
        val pct = (copied.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0)
        return "%.0f%% (%s / %s of archive)".format(pct, got, formatBytes(total))
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
        store.read(id)?.let { return it }
        if (store.exists(id))
        {
            // The job file is there but unreadable: the host lost power / was reset while the agent was
            // rewriting it. Say so (and let the operator start again) instead of "no job yet".
            val message = "Snapshot job state was damaged by a host restart — start the snapshot again"
            return SnapshotJob(
                jobId = id,
                url = "",
                destDir = "",
                phase = "failed",
                detail = message,
                failed = true,
                error = message,
            )
        }
        return null
    }
}
