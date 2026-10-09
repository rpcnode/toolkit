package rpcnode.toolkit.cdn.application.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

/**
 * Polls the local target list and keeps one worker per `network/env/type`.
 * A second poll never starts a second download of the same target.
 * Workers take a [jobs] permit only while fetching; idle workers just watch for a new VERSION.
 */
class WatchSnapshotsUseCase(
    private val source: SnapshotSource,
    private val store: SnapshotMirrorStore,
    private val scope: CoroutineScope,
    private val jobs: Int = 4,
    private val idleSec: Long = 60,
    private val retrySec: Long = 15,
    /** Public HTTP origin for rewritten Base manifests (`CDN_PUBLIC_ORIGIN`). */
    private val publicOrigin: String? = null,
)
{
    private val log = LoggerFactory.getLogger(WatchSnapshotsUseCase::class.java)
    private val gate = Semaphore(jobs.coerceIn(1, 16))
    private val lock = Mutex()
    private val workers = linkedMapOf<String, Job>()

    suspend fun run(pollSec: Long)
    {
        while (true)
        {
            tick()
            delay(pollSec.coerceAtLeast(5) * 1000)
        }
    }

    suspend fun tick()
    {
        val targets = try
        {
            source.listTargets()
        }
        catch (e: Exception)
        {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log.warn("target list failed: {}", e.message)
            return
        }
        if (targets == null)
        {
            log.warn("targets unavailable — keep current workers")
            return
        }
        val wanted = targets.associateBy { it.id }
        log.info("targets — {} network/env(s) required", wanted.size)
        lock.withLock {
            for ((id, job) in workers.toList())
            {
                if (id !in wanted)
                {
                    log.info("stop worker {} — removed from targets", id)
                    job.cancel()
                    workers.remove(id)
                }
            }
            for ((id, target) in wanted)
            {
                val running = workers[id]
                if (running != null && running.isActive)
                {
                    continue
                }
                log.info("start worker {}", id)
                workers[id] = scope.launch {
                    runWorker(target)
                }
            }
        }
        refreshIndex(wanted.values)
    }

    suspend fun activeIds(): Set<String> = lock.withLock {
        workers.filter { it.value.isActive }.keys.toSet()
    }

    private suspend fun runWorker(target: SnapshotTarget)
    {
        val id = target.id
        var lastVersion: String? = null
        while (scope.isActive)
        {
            try
            {
                val official = source.officialSnapshot(target)
                if (official == null)
                {
                    log.info("worker {} — no official archive yet", id)
                }
                else if (store.currentVersion(target.network, target.env, target.type) == official.version)
                {
                    if (lastVersion != official.version)
                    {
                        log.info("worker {} — already have {}", id, official.version)
                        lastVersion = official.version
                    }
                }
                else
                {
                    lastVersion = official.version
                    log.info(
                        "found new snapshot {} {} (disk had {})",
                        id,
                        official.version,
                        store.currentVersion(target.network, target.env, target.type) ?: "none",
                    )
                    gate.withPermit {
                        log.info("downloading {} {} {}", id, official.filename, official.version)
                        when (official.kind)
                        {
                            SnapshotMirrorKind.BASE_MANIFEST ->
                            {
                                val origin = publicOrigin?.trim()?.trimEnd('/').orEmpty()
                                if (origin.isEmpty())
                                {
                                    error(
                                        "CDN_PUBLIC_ORIGIN is required to mirror Base V2 " +
                                            "(rewrite manifest base_url for --manifest-url)",
                                    )
                                }
                                store.publishBaseManifest(
                                    network = target.network,
                                    env = target.env,
                                    type = target.type,
                                    version = official.version,
                                    manifestUrl = official.url,
                                    sizeBytes = official.sizeBytes,
                                    publicOrigin = origin,
                                )
                            }
                            SnapshotMirrorKind.ARCHIVE_FILE ->
                                store.publish(
                                    network = target.network,
                                    env = target.env,
                                    type = target.type,
                                    version = official.version,
                                    filename = official.filename,
                                    sourceUrl = official.url,
                                    sizeBytes = official.sizeBytes,
                                )
                        }
                    }
                    log.info("mirrored {} {}", id, official.version)
                    refreshIndex(listOf(target))
                }
            }
            catch (e: Exception)
            {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.warn("worker {} dropped, retry in {}s: {}", id, retrySec, e.message)
                lastVersion = null
                delay(retrySec.coerceAtLeast(5) * 1000)
                continue
            }
            delay(idleSec.coerceAtLeast(5) * 1000)
        }
    }

    private suspend fun refreshIndex(@Suppress("UNUSED_PARAMETER") targets: Collection<SnapshotTarget>)
    {
        // Always rebuild from on-disk site.json under the store lock so parallel
        // workers cannot drop each other's catalogue rows.
        store.rebuildPublicIndex()
    }
}
