package rpcnode.toolkit.agent.application.client

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.slf4j.LoggerFactory
import rpcnode.toolkit.clients.application.ClientBuildResult
import rpcnode.toolkit.clients.domain.model.ClientBuildSpec
import rpcnode.toolkit.clients.infrastructure.docker.DockerBuildRunner

data class HostBuildCommand(
    val jobId: String,
    val network: String,
    val program: String,
    val version: String,
    val tag: String,
    val platform: String,
    val dockerfile: String,
    val outputs: List<String>,
)

sealed interface HostBuildStart
{
    data object Accepted : HostBuildStart
    data class Busy(val detail: String) : HostBuildStart
    data class Unavailable(val detail: String) : HostBuildStart
    data class Invalid(val detail: String) : HostBuildStart
}

data class HostBuildStatus(
    /** `running`, `done`, `failed`, `unavailable` (this host cannot build) or `unknown`. */
    val phase: String,
    val detail: String = "",
    val logTail: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    /** The complete build log on this host (kept after the job ends, until [HostClientBuildUseCase] prunes it). */
    val logFile: String = "",
)

/**
 * Compiles a client for the panel on this host: `docker build` natively for this CPU architecture, the result kept
 * under [root] until the panel has fetched it. One build at a time (a compile already uses every core); a job id is
 * stable per release, so a repeated start re-attaches instead of compiling twice.
 */
class HostClientBuildUseCase(
    private val scope: CoroutineScope,
    private val docker: DockerBuildRunner = DockerBuildRunner(),
    private val root: Path = Path.of("/var/tmp/rpcnode-agent/builds"),
    private val hostArch: () -> String? = { ClientBuildSpec.normalizeArch(System.getProperty("os.arch")) },
    private val keepFinishedMillis: Long = 24 * 3600_000L,
    private val keepLogsMillis: Long = 7 * 24 * 3600_000L,
    /** Installs the buildx plugin when it is missing — done inside the job, it downloads a binary. */
    private val buildx: DockerBuildxInstaller = GithubBuildxInstaller(docker),
)
{
    private val log = LoggerFactory.getLogger(HostClientBuildUseCase::class.java)
    private val jobs = ConcurrentHashMap<String, Job>()

    private class Job(val dir: Path, val logPath: Path, val startedAt: Long = System.currentTimeMillis())
    {
        @Volatile var phase: String = "running"
        @Volatile var detail: String = ""
        @Volatile var files: List<String> = emptyList()
        val log = java.util.concurrent.ConcurrentLinkedDeque<String>()
        @Volatile var running: kotlinx.coroutines.Job? = null
    }

    @Synchronized
    fun start(cmd: HostBuildCommand): HostBuildStart
    {
        validate(cmd)?.let { return HostBuildStart.Invalid(it) }
        val arch = ClientBuildSpec.archOf(cmd.platform)
        if (hostArch() != arch)
        {
            return HostBuildStart.Unavailable("this host is ${hostArch() ?: "unknown"}, cannot natively build ${cmd.platform}")
        }
        jobs[cmd.jobId]?.let { existing ->
            when (existing.phase)
            {
                "running", "done" -> return HostBuildStart.Accepted
                else -> jobs.remove(cmd.jobId)
            }
        }
        jobs.entries.firstOrNull { it.value.phase == "running" }?.let {
            return HostBuildStart.Busy("already compiling ${it.key}")
        }
        docker.daemonProblem()?.let { return HostBuildStart.Unavailable(it) }

        pruneOld()
        val dir = root.resolve(cmd.jobId)
        runCatching { dir.toFile().deleteRecursively(); Files.createDirectories(dir) }
            .onFailure { return HostBuildStart.Unavailable("cannot use $dir: ${it.message}") }
        val job = Job(dir, logPath(cmd.jobId))
        jobs[cmd.jobId] = job
        job.running = scope.launch(Dispatchers.IO) {
            val writer = openLog(job.logPath)
            fun line(text: String)
            {
                job.log.addLast(text)
                while (job.log.size > LOG_LINES) job.log.pollFirst()
                writer?.let { runCatching { it.write(text); it.newLine(); it.flush() } }
            }
            line("# ${java.time.Instant.now()} client build ${cmd.program} ${cmd.tag} for ${cmd.platform} (job ${cmd.jobId})")
            val result = try
            {
                runInterruptible {
                    // A host without the buildx plugin gets it installed first (a few seconds) instead of being skipped.
                    buildx.install { text -> line(text) }?.let { reason ->
                        return@runInterruptible ClientBuildResult.Unavailable(reason)
                    }
                    docker.build(
                        cmd.dockerfile, cmd.platform, cmd.version, cmd.tag, cmd.outputs, dir,
                    ) { text -> line(text) }
                }
            }
            catch (e: Exception)
            {
                ClientBuildResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            when (result)
            {
                is ClientBuildResult.Built ->
                {
                    job.files = result.names
                    job.phase = "done"
                }
                is ClientBuildResult.Failed ->
                {
                    job.detail = result.error
                    job.phase = "failed"
                }
                is ClientBuildResult.Unavailable ->
                {
                    job.detail = result.reason
                    job.phase = "unavailable"
                }
            }
            line("# ${java.time.Instant.now()} finished: ${job.phase}${if (job.detail.isNotBlank()) " — " + job.detail.lineSequence().first() else ""}")
            runCatching { writer?.close() }
            log.info("client build {} finished: {} (log {})", cmd.jobId, job.phase, job.logPath)
        }
        log.info("client build {} started ({} {})", cmd.jobId, cmd.program, cmd.tag)
        return HostBuildStart.Accepted
    }

    fun status(jobId: String): HostBuildStatus
    {
        val job = jobs[jobId]
            ?: return HostBuildStatus(
                phase = "unknown",
                detail = "no such job",
                logFile = logPath(jobId).takeIf { ID.matches(jobId) && Files.isRegularFile(it) }?.toString().orEmpty(),
            )
        return HostBuildStatus(job.phase, job.detail, job.log.toList().takeLast(TAIL), job.files, job.logPath.toString())
    }

    /** The last [lines] lines of the job's log file — still readable after the job finished or the agent restarted. */
    fun logTail(jobId: String, lines: Int = 200): List<String>?
    {
        if (!ID.matches(jobId))
        {
            return null
        }
        val file = logPath(jobId)
        if (!Files.isRegularFile(file))
        {
            return null
        }
        return runCatching {
            ArrayDeque<String>().also { tail ->
                Files.newBufferedReader(file).useLines { seq ->
                    seq.forEach {
                        tail.addLast(it)
                        if (tail.size > lines.coerceIn(1, 5_000)) tail.removeFirst()
                    }
                }
            }.toList()
        }.getOrNull()
    }

    private fun logPath(jobId: String): Path = root.resolve("$jobId.log")

    private fun openLog(path: Path): java.io.BufferedWriter? = runCatching {
        Files.createDirectories(path.parent)
        // a retry of the same job appends: the earlier attempt's failure stays readable above the new one
        Files.newBufferedWriter(path, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    }.getOrNull()

    /** The finished file [name] of [jobId], only when the job published it. */
    fun artifact(jobId: String, name: String): Path?
    {
        val job = jobs[jobId] ?: return null
        if (job.phase != "done" || name !in job.files)
        {
            return null
        }
        return job.dir.resolve(name).takeIf { Files.isRegularFile(it) }
    }

    fun release(jobId: String)
    {
        val job = jobs[jobId] ?: return
        if (job.phase == "running")
        {
            return
        }
        jobs.remove(jobId)
        runCatching { job.dir.toFile().deleteRecursively() }
    }

    private fun pruneOld()
    {
        val cutoff = System.currentTimeMillis() - keepLogsMillis
        runCatching {
            Files.list(root).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".log") }
                    .filter { Files.getLastModifiedTime(it).toMillis() < cutoff }
                    .forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
        val now = System.currentTimeMillis()
        jobs.entries.removeIf { (id, job) ->
            val stale = job.phase != "running" && now - job.startedAt > keepFinishedMillis
            if (stale) runCatching { job.dir.toFile().deleteRecursively() }
            stale.also { if (it) log.info("dropped finished client build {}", id) }
        }
    }

    private fun validate(cmd: HostBuildCommand): String?
    {
        if (!ID.matches(cmd.jobId)) return "invalid job id"
        if (cmd.platform !in ClientBuildSpec.SUPPORTED_PLATFORMS) return "unsupported platform ${cmd.platform}"
        if (cmd.dockerfile.isBlank() || cmd.dockerfile.length > MAX_DOCKERFILE) return "missing or oversized Dockerfile"
        if (cmd.outputs.isEmpty() || cmd.outputs.any { !OUTPUT.matches(it) }) return "invalid outputs"
        if (cmd.version.isBlank() || cmd.tag.isBlank()) return "version and tag are required"
        return null
    }

    companion object
    {
        private val ID = Regex("[A-Za-z0-9._-]{1,120}")
        private val OUTPUT = Regex("[A-Za-z0-9._-]{1,64}")
        private const val MAX_DOCKERFILE = 64 * 1024
        private const val LOG_LINES = 400
        private const val TAIL = 40
    }
}
