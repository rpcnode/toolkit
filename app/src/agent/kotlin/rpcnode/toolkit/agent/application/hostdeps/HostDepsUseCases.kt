package rpcnode.toolkit.agent.application.hostdeps

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.infrastructure.hostdeps.HostDepsInstaller

class ProbeHostDepsUseCase(
    private val installer: HostDepsInstaller = HostDepsInstaller(),
)
{
    operator fun invoke(deps: List<HostDepRequest>): List<HostDepProbeItem> =
        installer.probe(deps)
}

class HostDepsJobStore
{
    private val jobs = ConcurrentHashMap<String, HostDepsJobSnapshot>()

    fun get(jobId: String): HostDepsJobSnapshot? = jobs[jobId.trim()]

    fun put(snap: HostDepsJobSnapshot)
    {
        jobs[snap.jobId] = snap
    }
}

class StartHostDepsInstallUseCase(
    private val store: HostDepsJobStore,
    private val installer: HostDepsInstaller = HostDepsInstaller(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
)
{
    private val log = LoggerFactory.getLogger(StartHostDepsInstallUseCase::class.java)
    private val mutex = Mutex()
    private val running = AtomicBoolean(false)

    suspend operator fun invoke(
        jobIdRaw: String?,
        deps: List<HostDepRequest>,
    ): StartHostDepsInstallResult
    {
        if (deps.isEmpty())
        {
            return StartHostDepsInstallResult.Invalid
        }
        val jobId = jobIdRaw?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        mutex.withLock {
            val existing = store.get(jobId)
            if (existing != null && !existing.ready && !existing.failed)
            {
                return StartHostDepsInstallResult.AlreadyRunning(jobId)
            }
            if (running.get())
            {
                val other = store.get(jobId)
                if (other != null && !other.ready && !other.failed)
                {
                    return StartHostDepsInstallResult.AlreadyRunning(other.jobId)
                }
            }
            val items = deps.map {
                HostDepProgressItem(id = it.id, status = "pending", detail = "queued")
            }
            store.put(
                HostDepsJobSnapshot(
                    jobId = jobId,
                    phase = "starting",
                    detail = "Starting host dependency install",
                    pct = 0,
                    items = items,
                ),
            )
            running.set(true)
            scope.launch {
                try
                {
                    runJob(jobId, deps)
                }
                finally
                {
                    running.set(false)
                }
            }
            return StartHostDepsInstallResult.Started(jobId)
        }
    }

    private fun runJob(jobId: String, deps: List<HostDepRequest>)
    {
        val total = deps.size.coerceAtLeast(1)
        val items = deps.map {
            HostDepProgressItem(id = it.id, status = "pending", detail = "queued")
        }.toMutableList()
        val logs = mutableListOf<String>()
        fun push(line: String)
        {
            logs += line
            while (logs.size > 80)
            {
                logs.removeAt(0)
            }
        }
        fun publish(
            phase: String,
            detail: String,
            currentId: String = "",
            pct: Int = 0,
            ready: Boolean = false,
            failed: Boolean = false,
            error: String = "",
        )
        {
            store.put(
                HostDepsJobSnapshot(
                    jobId = jobId,
                    phase = phase,
                    detail = detail,
                    pct = pct.coerceIn(0, 100),
                    currentId = currentId,
                    items = items.toList(),
                    ready = ready,
                    failed = failed,
                    error = error,
                    logTail = logs.toList(),
                ),
            )
        }

        for ((index, dep) in deps.withIndex())
        {
            val pctBefore = (index * 100) / total
            items[index] = HostDepProgressItem(id = dep.id, status = "installing", detail = "checking")
            publish(
                phase = "installing",
                detail = "Ensuring ${dep.id}",
                currentId = dep.id,
                pct = pctBefore,
            )
            push("probe ${dep.id} (${dep.kind})")
            val probed = installer.probe(listOf(dep)).firstOrNull()
            if (probed?.present == true)
            {
                items[index] = HostDepProgressItem(id = dep.id, status = "present", detail = probed.detail)
                push("${dep.id}: already present")
                publish(
                    phase = "installing",
                    detail = "${dep.id} present",
                    currentId = dep.id,
                    pct = ((index + 1) * 100) / total,
                )
                continue
            }
            push("install ${dep.id}")
            items[index] = HostDepProgressItem(id = dep.id, status = "installing", detail = "installing")
            publish(
                phase = "installing",
                detail = "Installing ${dep.id}",
                currentId = dep.id,
                pct = pctBefore,
            )
            val result = try
            {
                installer.installOne(dep)
            }
            catch (e: Exception)
            {
                log.warn("host dep {} failed: {}", dep.id, e.message)
                HostDepProbeItem(
                    id = dep.id,
                    present = false,
                    detail = e.message?.ifBlank { null } ?: "install failed",
                )
            }
            // Poll until present or give up after install attempt + re-probe.
            var present = result.present
            var detail = result.detail
            if (!present)
            {
                repeat(5)
                {
                    Thread.sleep(800)
                    val again = installer.probe(listOf(dep)).firstOrNull()
                    if (again?.present == true)
                    {
                        present = true
                        detail = again.detail.ifBlank { "present" }
                        return@repeat
                    }
                    detail = again?.detail?.ifBlank { null } ?: detail
                }
            }
            if (!present)
            {
                items[index] = HostDepProgressItem(id = dep.id, status = "failed", detail = detail)
                push("${dep.id}: failed — $detail")
                publish(
                    phase = "failed",
                    detail = "Failed: ${dep.id}",
                    currentId = dep.id,
                    pct = pctBefore,
                    failed = true,
                    error = detail,
                )
                return
            }
            items[index] = HostDepProgressItem(id = dep.id, status = "present", detail = detail)
            push("${dep.id}: ready")
            publish(
                phase = "installing",
                detail = "${dep.id} ready",
                currentId = dep.id,
                pct = ((index + 1) * 100) / total,
            )
        }
        publish(
            phase = "complete",
            detail = "All host dependencies ready",
            pct = 100,
            ready = true,
        )
        push("complete")
    }
}

class GetHostDepsProgressUseCase(
    private val store: HostDepsJobStore,
)
{
    operator fun invoke(jobId: String): HostDepsJobSnapshot? = store.get(jobId)
}
