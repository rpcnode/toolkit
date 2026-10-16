package rpcnode.toolkit.agent.application.node

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.infrastructure.proc.SystemdTransientDownload
import rpcnode.toolkit.agent.infrastructure.http.SnapshotHttpDownload
import rpcnode.toolkit.agent.infrastructure.proc.runningAsRoot
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

data class RemovalStep(
    val id: String,
    val title: String,
    /** pending | running | done | skipped | failed */
    val status: String = "pending",
    val detail: String = "",
)

data class RemovalState(
    val nodeId: String,
    val steps: List<RemovalStep>,
    val done: Boolean = false,
    val failed: Boolean = false,
    val error: String = "",
)

data class RemoveStepsCommand(
    val nodeId: String,
    val network: String,
    val env: String,
    /** Node directories on the chosen disk(s) (all disk-layout roles). */
    val dirs: List<String>,
    /** false = keep chain data: only stop and delete the service. */
    val wipeData: Boolean,
)

sealed interface StartRemovalResult
{
    data class Started(val state: RemovalState) : StartRemovalResult
    data class AlreadyRunning(val state: RemovalState) : StartRemovalResult
    data object NotRoot : StartRemovalResult
    data class Invalid(val message: String) : StartRemovalResult
}

/** Host operations behind the removal steps — replaceable in tests. */
interface RemovalOps
{
    fun unitActive(network: String, env: String): Boolean
    fun units(network: String, env: String, nodeDir: Path?): List<String>
    fun stop(network: String, env: String, nodeDir: Path?): HostNodeStartResult
    fun removeUnits(units: List<String>, network: String, env: String): HostNodeStartResult
    fun removeSnapshotUnit(nodeId: String)

    /**
     * Brings a clean-up script written by an older agent up to date before it runs (it is written at the node's Start, so an old node
     * keeps an old script). Returns how many were rewritten.
     */
    fun refreshRemoveHooks(network: String, dirs: List<Path>): Int = 0

    /** True when a node folder carries a chain clean-up script (TON: drops the indexer's database, removes MyTonCtrl). */
    fun hasRemoveHook(dirs: List<Path>): Boolean = false

    /** Runs those scripts (the node is stopped, its files are still there). */
    fun runRemoveHooks(dirs: List<Path>, wipe: Boolean): HostNodeLaunchSupport.HookResult = HostNodeLaunchSupport.HookResult.NoHook

    /** Stops running snapshot downloads that write under [dirs]; returns how many were stopped. */
    fun stopSnapshots(dirs: List<Path>): Int

    /** Deletes [path] and everything below it; [onFile] is called as files go. false = there was nothing to delete. */
    fun deleteTree(path: Path, onFile: (deleted: Long) -> Unit): Boolean
}

class HostRemovalOps(private val stopSnapshotJobs: (List<Path>) -> Int = { 0 }) : RemovalOps
{
    override fun stopSnapshots(dirs: List<Path>): Int = stopSnapshotJobs(dirs)

    override fun unitActive(network: String, env: String): Boolean
    {
        val unit = HostNodeLaunchSupport.unitName(network, env)
        return runCatching {
            val p = ProcessBuilder("systemctl", "is-active", unit).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out == "active" || out == "activating"
        }.getOrDefault(false)
    }

    override fun units(network: String, env: String, nodeDir: Path?): List<String> =
        HostNodeLaunchSupport.nodeUnits(network, env, nodeDir)

    override fun stop(network: String, env: String, nodeDir: Path?): HostNodeStartResult =
        HostNodeLaunchSupport.stopUnit(network, env, nodeDir)

    override fun removeUnits(units: List<String>, network: String, env: String): HostNodeStartResult =
        HostNodeLaunchSupport.removeUnits(units, network, env)

    override fun refreshRemoveHooks(network: String, dirs: List<Path>): Int =
        rpcnode.toolkit.agent.infrastructure.proc.RemoveHookRefresh.refresh(network, dirs)

    override fun hasRemoveHook(dirs: List<Path>): Boolean =
        dirs.any { Files.isRegularFile(it.resolve(HostNodeLaunchSupport.REMOVE_HOOK)) }

    override fun runRemoveHooks(dirs: List<Path>, wipe: Boolean): HostNodeLaunchSupport.HookResult
    {
        val outputs = mutableListOf<String>()
        for (dir in dirs)
        {
            when (val result = HostNodeLaunchSupport.runRemoveHook(dir, wipe))
            {
                HostNodeLaunchSupport.HookResult.NoHook -> Unit
                is HostNodeLaunchSupport.HookResult.Done -> outputs += result.output
                is HostNodeLaunchSupport.HookResult.Failed -> return result
            }
        }
        return if (outputs.isEmpty()) HostNodeLaunchSupport.HookResult.NoHook
        else HostNodeLaunchSupport.HookResult.Done(outputs.joinToString("; "))
    }

    override fun removeSnapshotUnit(nodeId: String)
    {
        // A snapshot download that was still running leaves a transient `rpcnode-snap-…` unit behind.
        runCatching {
            SystemdTransientDownload.stop(SnapshotHttpDownload.unitNameForLabel("snapshot-$nodeId"), destHint = null)
        }
    }

    override fun deleteTree(path: Path, onFile: (deleted: Long) -> Unit): Boolean
    {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        {
            return false
        }
        var count = 0L
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>()
            {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult
                {
                    Files.deleteIfExists(file)
                    count++
                    if (count % 500L == 0L) onFile(count)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    if (exc is NoSuchFileException) FileVisitResult.CONTINUE else throw exc

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult
                {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        onFile(count)
        return true
    }
}

/**
 * Node removal on the host as a background job with visible steps:
 * 1. stop the node if it runs (1b. run the chain's clean-up script, e.g. drop the TON indexer database), 2. delete its files (node folders on the chosen disk and `/opt/<network>/<env>`),
 * 3. delete the systemd service. Clearing terabytes takes minutes, so this never runs inside one HTTP call.
 */
class RemoveNodeStepsUseCase(
    private val registry: RunningNodeRegistry,
    private val scope: CoroutineScope,
    private val ops: RemovalOps = HostRemovalOps(),
    private val isRoot: () -> Boolean = ::runningAsRoot,
    private val optRoot: Path = Path.of("/opt"),
)
{
    private val log = LoggerFactory.getLogger(RemoveNodeStepsUseCase::class.java)
    private val states = ConcurrentHashMap<String, RemovalState>()

    fun progress(nodeId: String): RemovalState? = states[nodeId.trim()]

    fun start(command: RemoveStepsCommand): StartRemovalResult
    {
        if (!isRoot())
        {
            return StartRemovalResult.NotRoot
        }
        val nodeId = command.nodeId.trim()
        val network = command.network.trim().lowercase()
        val env = command.env.trim().lowercase()
        if (nodeId.isEmpty() || !SEGMENT.matches(network) || !SEGMENT.matches(env))
        {
            return StartRemovalResult.Invalid("node id, network and env are required")
        }
        val dirs = mutableListOf<Path>()
        for (raw in command.dirs)
        {
            val path = safeNodeDir(raw) ?: return StartRemovalResult.Invalid("refusing unsafe directory: $raw")
            if (path !in dirs) dirs.add(path)
        }
        val running = states[nodeId]
        if (running != null && !running.done && !running.failed)
        {
            return StartRemovalResult.AlreadyRunning(running)
        }
        val initial = RemovalState(
            nodeId = nodeId,
            steps = listOfNotNull(
                RemovalStep("stop", "Stop the node"),
                RemovalStep("database", "Clean up chain services and database").takeIf { ops.hasRemoveHook(dirs) },
                RemovalStep("files", "Delete files"),
                RemovalStep("service", "Delete the service"),
            ),
        )
        states[nodeId] = initial
        scope.launch(Dispatchers.IO) {
            try
            {
                run(command.copy(network = network, env = env), dirs)
            }
            catch (e: Exception)
            {
                log.warn("node removal {} failed: {}", nodeId, e.message)
                fail(nodeId, "service", e.message ?: e.javaClass.simpleName, onlyIfRunning = true)
            }
        }
        return StartRemovalResult.Started(initial)
    }

    private fun run(command: RemoveStepsCommand, dirs: List<Path>)
    {
        val nodeId = command.nodeId
        val network = command.network
        val env = command.env
        val hint = dirs.firstOrNull()

        // 1. stop — the node, and any snapshot download still writing into its folders (deleting files under a
        // live aria2/tar leaves it filling an invisible deleted file and eating the disk)
        setStep(nodeId, "stop", "running", "")
        val snapshotsStopped = ops.stopSnapshots(dirs + listOf(optRoot.resolve(network).resolve(env)))
        if (ops.unitActive(network, env))
        {
            when (val stopped = ops.stop(network, env, hint))
            {
                is HostNodeStartResult.Failed -> return fail(nodeId, "stop", stopped.detail)
                else -> setStep(nodeId, "stop", "done", if (snapshotsStopped > 0) "node and $snapshotsStopped snapshot download(s) stopped" else "node stopped")
            }
        }
        else
        {
            if (snapshotsStopped > 0) setStep(nodeId, "stop", "done", "$snapshotsStopped snapshot download(s) stopped")
            else setStep(nodeId, "stop", "skipped", "not running")
        }

        // 1b. chain clean-up that needs the node folder (TON: drop the indexer's database, remove MyTonCtrl and ton_http_api)
        ops.refreshRemoveHooks(network, dirs)
        if (ops.hasRemoveHook(dirs))
        {
            setStep(nodeId, "database", "running", "")
            when (val hook = ops.runRemoveHooks(dirs, command.wipeData))
            {
                is HostNodeLaunchSupport.HookResult.Failed -> return fail(nodeId, "database", hook.detail)
                is HostNodeLaunchSupport.HookResult.Done -> setStep(nodeId, "database", "done", hook.output)
                HostNodeLaunchSupport.HookResult.NoHook -> setStep(nodeId, "database", "skipped", "nothing to drop")
            }
        }

        // The service names (incl. companions such as lighthouse) are recorded inside the node folder:
        // read them now, step 2 deletes that folder.
        val units = (dirs.flatMap { ops.units(network, env, it) } + ops.units(network, env, null)).distinct()

        // 2. files
        if (!command.wipeData)
        {
            setStep(nodeId, "files", "skipped", "chain data kept on disk")
        }
        else
        {
            setStep(nodeId, "files", "running", "")
            val targets = (dirs + listOf(optRoot.resolve(network).resolve(env))).distinct()
            var removed = 0
            for (target in targets)
            {
                setStep(nodeId, "files", "running", "deleting $target")
                val existed = try
                {
                    ops.deleteTree(target) { n -> setStep(nodeId, "files", "running", "deleting $target — $n files") }
                }
                catch (e: Exception)
                {
                    return fail(nodeId, "files", "$target: ${e.message ?: e.javaClass.simpleName}")
                }
                if (!existed)
                {
                    continue
                }
                removed++
                pruneEmptyParents(target, network, env)
            }
            setStep(
                nodeId,
                "files",
                "done",
                if (removed == 0) "nothing on disk" else "deleted $removed folder${if (removed == 1) "" else "s"}",
            )
        }

        // 3. service
        setStep(nodeId, "service", "running", "")
        ops.removeSnapshotUnit(nodeId)
        when (val result = ops.removeUnits(units, network, env))
        {
            is HostNodeStartResult.Failed -> return fail(nodeId, "service", result.detail)
            else -> Unit
        }
        registry.remove(nodeId)
        setStep(nodeId, "service", "done", units.joinToString(", ").ifEmpty { "no service installed" })
        states.computeIfPresent(nodeId) { _, s -> s.copy(done = true) }
    }

    /** `.../<network>/<env>/<leaf>` is gone: drop `<env>` and `<network>` too when they are empty. */
    private fun pruneEmptyParents(removed: Path, network: String, env: String)
    {
        var dir = removed.parent
        for (expected in listOf(env, network))
        {
            if (dir == null || dir.fileName?.toString() != expected)
            {
                return
            }
            try
            {
                Files.delete(dir)
            }
            catch (_: IOException)
            {
                return // not empty (other leaves / other envs live there)
            }
            dir = dir.parent
        }
    }

    private fun setStep(nodeId: String, stepId: String, status: String, detail: String)
    {
        states.computeIfPresent(nodeId) { _, state ->
            state.copy(steps = state.steps.map { if (it.id == stepId) it.copy(status = status, detail = detail) else it })
        }
    }

    private fun fail(nodeId: String, stepId: String, message: String, onlyIfRunning: Boolean = false)
    {
        states.computeIfPresent(nodeId) { _, state ->
            if (onlyIfRunning && (state.done || state.failed))
            {
                return@computeIfPresent state
            }
            state.copy(
                steps = state.steps.map { if (it.id == stepId) it.copy(status = "failed", detail = message) else it },
                failed = true,
                error = message,
            )
        }
    }

    companion object
    {
        private val SEGMENT = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

        private val PROTECTED_TOP = setOf(
            "bin", "boot", "dev", "etc", "lib", "lib32", "lib64", "proc", "run", "sbin", "sys", "usr", "snap",
        )

        /**
         * A node folder is deleted recursively, so it must look like one: absolute, normalised, at least
         * three levels deep, never a system directory and never RpcNode's own `/opt/rpcnode`.
         */
        fun safeNodeDir(raw: String): Path?
        {
            val text = raw.trim()
            if (text.isEmpty() || !text.startsWith("/") || ".." in text.split('/'))
            {
                return null
            }
            val path = Path.of(text).normalize()
            val parts = (0 until path.nameCount).map { path.getName(it).toString() }
            if (parts.size < 3 || parts.first() in PROTECTED_TOP)
            {
                return null
            }
            if (parts.first() == "opt" && parts.getOrNull(1) == "rpcnode")
            {
                return null
            }
            if (parts.first() == "var" && parts.getOrNull(1) in setOf("lib", "log", "cache") && parts.size < 4)
            {
                return null
            }
            if (parts.first() in setOf("var", "root", "home") && parts.any { it == "rpcnode-agent" || it == "docker" })
            {
                return null
            }
            return path
        }
    }
}
