package rpcnode.toolkit.agent.application.client

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.application.enroll.PanelEnrollmentStore
import rpcnode.toolkit.agent.application.node.NodeHeightPlan
import rpcnode.toolkit.agent.application.node.NodeLaunchPlan
import rpcnode.toolkit.agent.application.node.NodeStartCommand
import rpcnode.toolkit.agent.application.node.NodeStartProcessResult
import rpcnode.toolkit.agent.application.node.StartNodeProcessUseCase
import rpcnode.toolkit.agent.infrastructure.node.readNodeClientVersion
import rpcnode.toolkit.agent.infrastructure.proc.runningAsRoot
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

data class ClientUpdateCommand(
    val nodeId: String,
    val network: String,
    val env: String,
    val nodeDir: String,
    val configAssignments: Map<String, String> = emptyMap(),
    val configFormat: String = "hoocon",
    val configFile: String? = null,
    val configIniSection: String? = null,
    val configOmitIniKeys: Set<String> = emptySet(),
    val httpPort: Int = 0,
    val program: String = "",
    val clientVersion: String = "",
    val launch: NodeLaunchPlan = NodeLaunchPlan(kind = "", entry = ""),
    val height: NodeHeightPlan = NodeHeightPlan(kind = ""),
)

sealed interface ClientUpdateAcceptResult
{
    data class Accepted(val snapshot: ClientUpdateSnapshot) : ClientUpdateAcceptResult
    data object Busy : ClientUpdateAcceptResult
    data object NotRoot : ClientUpdateAcceptResult
    data object InvalidNodeDir : ClientUpdateAcceptResult
    data object InvalidLaunch : ClientUpdateAcceptResult
}

sealed interface ClientRollbackResult
{
    data class Ok(val snapshot: ClientUpdateSnapshot) : ClientRollbackResult
    data object NoPrevious : ClientRollbackResult
    data object Busy : ClientRollbackResult
    data object NotRoot : ClientRollbackResult
    data object InvalidNodeDir : ClientRollbackResult
    data class Failed(val detail: String) : ClientRollbackResult
}

/**
 * Safe client update on the host: stop unit, download into staging, keep previous, promote, trial start.
 * Pushes stop / updating / started webhooks to the panel for the admin modal.
 * On failure leaves `phase=error` with log tail — operator must call [rollback] to restore previous.
 */
class UpdateClientOnHostUseCase(
    private val sync: SyncClientFromPanelUseCase,
    private val startNode: StartNodeProcessUseCase,
    private val state: ClientUpdateStateStore,
    private val scope: CoroutineScope,
    private val enrollment: PanelEnrollmentStore,
    private val notifyPanel: NotifyPanelClientUpdate,
    private val agentToken: String,
    private val isRoot: () -> Boolean = ::runningAsRoot,
    private val trialActiveSeconds: Long = 45,
    private val logTailLines: Int = 80,
)
{
    private val log = LoggerFactory.getLogger(UpdateClientOnHostUseCase::class.java)

    fun status(nodeId: String = "", network: String = "", env: String = ""): ClientUpdateSnapshot =
        state.find(nodeId = nodeId, network = network, env = env)

    fun accept(command: ClientUpdateCommand): ClientUpdateAcceptResult
    {
        if (!isRoot())
        {
            return ClientUpdateAcceptResult.NotRoot
        }
        val nodeDir = command.nodeDir.trim()
        if (nodeDir.isEmpty() || !nodeDir.startsWith("/") || nodeDir.contains(".."))
        {
            return ClientUpdateAcceptResult.InvalidNodeDir
        }
        if (command.launch.kind.isBlank() || command.launch.entry.isBlank())
        {
            return ClientUpdateAcceptResult.InvalidLaunch
        }
        val nodeId = command.nodeId.trim()
        if (nodeId.isEmpty())
        {
            return ClientUpdateAcceptResult.InvalidNodeDir
        }
        val current = state.get(nodeId)
        if (current.phase.equals("updating", ignoreCase = true))
        {
            return ClientUpdateAcceptResult.Busy
        }
        val local = readNodeClientVersion(nodeDir).ifEmpty { command.clientVersion.trim() }
        val latest = command.clientVersion.trim().ifEmpty { local }
        val previousHint = ClientStagingLayout.readPreviousVersion(Path.of(nodeDir)).ifEmpty { local }
        val snap = state.set(
            ClientUpdateSnapshot(
                nodeId = nodeId,
                network = command.network.trim().lowercase(),
                env = command.env.trim().lowercase(),
                nodeDir = nodeDir,
                local = local,
                latest = latest,
                previousVersion = previousHint,
                updateAvailable = latest.isNotEmpty() && local.isNotEmpty() &&
                    !local.equals(latest, ignoreCase = true),
                phase = "updating",
                step = "check",
                detail = "Preparing client update",
                pct = 5,
            ),
        )
        scope.launch(Dispatchers.IO) {
            runJob(command)
        }
        return ClientUpdateAcceptResult.Accepted(snap)
    }

    suspend fun rollback(
        nodeIdRaw: String,
        networkRaw: String = "",
        envRaw: String = "",
    ): ClientRollbackResult = withContext(Dispatchers.IO) {
        if (!isRoot())
        {
            return@withContext ClientRollbackResult.NotRoot
        }
        val snap = state.find(nodeId = nodeIdRaw, network = networkRaw, env = envRaw)
        val nodeId = snap.nodeId.trim().ifEmpty { nodeIdRaw.trim() }
        val nodeDir = snap.nodeDir.trim()
        if (nodeDir.isEmpty() || !nodeDir.startsWith("/") || nodeDir.contains(".."))
        {
            return@withContext ClientRollbackResult.InvalidNodeDir
        }
        if (snap.phase.equals("updating", ignoreCase = true))
        {
            return@withContext ClientRollbackResult.Busy
        }
        val root = Path.of(nodeDir)
        val previous = ClientStagingLayout.previousDir(root)
        if (!Files.isDirectory(previous) || ClientStagingLayout.listArtifactNames(previous).isEmpty())
        {
            return@withContext ClientRollbackResult.NoPrevious
        }
        try
        {
            val network = snap.network.ifEmpty { networkRaw.trim().lowercase() }
            val env = snap.env.ifEmpty { envRaw.trim().lowercase() }
            HostNodeLaunchSupport.stopUnit(network, env, root)
            val restored = ClientStagingLayout.restorePreviousToLive(root)
            state.update(nodeId) {
                it.copy(
                    phase = "updating",
                    step = "install",
                    detail = "Restoring previous client $restored",
                    pct = 70,
                    lastError = "",
                    previousVersion = restored,
                )
            }
            when (val startResult = HostNodeLaunchSupport.restartUnit(network, env, root))
            {
                is HostNodeStartResult.Started, is HostNodeStartResult.Pending ->
                {
                    val done = state.update(nodeId) {
                        it.copy(
                            phase = "idle",
                            step = "done",
                            detail = "Restored previous client $restored",
                            pct = 100,
                            local = restored,
                            lastError = "",
                            logTail = "",
                            updateAvailable = true,
                        )
                    }
                    ClientRollbackResult.Ok(done)
                }
                is HostNodeStartResult.Failed ->
                {
                    val failed = state.update(nodeId) {
                        it.copy(
                            phase = "error",
                            step = "install",
                            detail = "Rollback restore ok but start failed",
                            lastError = startResult.detail,
                            pct = 100,
                            local = restored,
                            logTail = readLogTail(root, null),
                        )
                    }
                    log.warn("client rollback start failed for {}: {}", nodeId, startResult.detail)
                    ClientRollbackResult.Ok(failed)
                }
                HostNodeStartResult.InvalidLaunch ->
                    ClientRollbackResult.Failed("invalid launch")
            }
        }
        catch (e: Exception)
        {
            ClientRollbackResult.Failed(e.message ?: "rollback failed")
        }
    }

    private suspend fun runJob(command: ClientUpdateCommand)
    {
        val nodeId = command.nodeId.trim()
        val nodeDir = Path.of(command.nodeDir.trim())
        val network = command.network.trim().lowercase()
        val env = command.env.trim().lowercase()
        val logFile = command.launch.logFile
        try
        {
            patch(nodeId) {
                it.copy(step = "stopped", detail = "Stopping unit before update", pct = 10)
            }
            HostNodeLaunchSupport.stopUnit(network, env, nodeDir)
            report(nodeId, eventId = "stopped", eventLabel = "Stopped")

            val staging = ClientStagingLayout.updateDir(nodeDir)
            ClientStagingLayout.ensureEmptyDir(staging)
            patch(nodeId) {
                it.copy(step = "updating", detail = "Downloading client into staging", pct = 25)
            }
            report(nodeId, eventId = "updating", eventLabel = "Updating")
            when (
                val synced = sync(
                    ClientSyncCommand(
                        network = network,
                        env = env,
                        nodeDir = staging.toString(),
                        configAssignments = command.configAssignments,
                        configFormat = command.configFormat,
                        configFile = command.configFile,
                        configIniSection = command.configIniSection,
                        configOmitIniKeys = command.configOmitIniKeys,
                    ),
                )
            )
            {
                is ClientSyncResult.Ok -> Unit
                ClientSyncResult.MissingPanelUrl ->
                    return fail(nodeId, nodeDir, network, env, logFile, "missing_panel_url", "Agent is not enrolled (no panel URL)")
                ClientSyncResult.InvalidNodeDir ->
                    return fail(nodeId, nodeDir, network, env, logFile, "invalid_node_dir", "Invalid staging path")
                is ClientSyncResult.PlanMissing ->
                    return fail(nodeId, nodeDir, network, env, logFile, "plan_missing", synced.detail)
                is ClientSyncResult.DownloadFailed ->
                    return fail(nodeId, nodeDir, network, env, logFile, "download_failed", synced.detail)
                is ClientSyncResult.PatchFailed ->
                    return fail(nodeId, nodeDir, network, env, logFile, "patch_failed", synced.detail)
            }

            patch(nodeId) {
                it.copy(step = "updating", detail = "Keeping previous client and promoting staging", pct = 55)
            }
            report(nodeId)
            val artifactNames = ClientStagingLayout.listArtifactNames(staging)
            val previousVersion = ClientStagingLayout.snapshotLiveToPrevious(nodeDir, artifactNames)
            ClientStagingLayout.promoteStagingToLive(nodeDir, staging)
            val newVersion = readNodeClientVersion(nodeDir.toString())
                .ifEmpty { command.clientVersion.trim() }

            patch(nodeId) {
                it.copy(
                    step = "updating",
                    detail = "Starting node on new client $newVersion",
                    pct = 75,
                    previousVersion = previousVersion.ifEmpty { it.previousVersion },
                    local = newVersion,
                )
            }
            report(nodeId)

            when (
                val started = startNode(
                    NodeStartCommand(
                        nodeId = nodeId,
                        network = network,
                        env = env,
                        nodeDir = nodeDir.toString(),
                        configFile = command.configFile,
                        httpPort = command.httpPort,
                        program = command.program,
                        clientVersion = newVersion,
                        launch = command.launch,
                        height = command.height,
                    ),
                )
            )
            {
                is NodeStartProcessResult.Started -> Unit
                is NodeStartProcessResult.Pending ->
                {
                    patch(nodeId) {
                        it.copy(
                            phase = "idle",
                            step = "started",
                            detail = started.detail,
                            pct = 100,
                            local = newVersion,
                            lastError = "",
                            updateAvailable = false,
                        )
                    }
                    report(nodeId, eventId = "started", eventLabel = "Started")
                    return
                }
                is NodeStartProcessResult.Failed ->
                    return fail(nodeId, nodeDir, network, env, logFile, "start_failed", started.detail)
                NodeStartProcessResult.NotRoot ->
                    return fail(nodeId, nodeDir, network, env, logFile, "not_root", "Agent is not root")
                NodeStartProcessResult.InvalidLaunch ->
                    return fail(nodeId, nodeDir, network, env, logFile, "invalid_launch", "Invalid launch recipe")
                NodeStartProcessResult.UnsupportedNetwork ->
                    return fail(nodeId, nodeDir, network, env, logFile, "unsupported_network", "Unsupported network")
                NodeStartProcessResult.InvalidNodeDir ->
                    return fail(nodeId, nodeDir, network, env, logFile, "invalid_node_dir", "Invalid node dir")
            }

            patch(nodeId) {
                it.copy(step = "updating", detail = "Waiting for unit to stay active", pct = 85)
            }
            report(nodeId)
            val unit = HostNodeLaunchSupport.unitName(network, env)
            if (!waitUnitActive(unit))
            {
                return fail(
                    nodeId,
                    nodeDir,
                    network,
                    env,
                    logFile,
                    "unit_not_active",
                    "systemd unit $unit did not stay active",
                )
            }

            patch(nodeId) {
                it.copy(
                    phase = "idle",
                    step = "started",
                    detail = "Client updated to $newVersion",
                    pct = 100,
                    local = newVersion,
                    latest = newVersion,
                    lastError = "",
                    logTail = "",
                    updateAvailable = false,
                )
            }
            report(nodeId, eventId = "started", eventLabel = "Started")
        }
        catch (e: Exception)
        {
            log.warn("client update failed for {}: {}", nodeId, e.message)
            fail(nodeId, nodeDir, network, env, logFile, "update_failed", e.message ?: "update failed")
        }
    }

    private fun waitUnitActive(unit: String): Boolean
    {
        val deadline = System.nanoTime() + trialActiveSeconds * 1_000_000_000L
        var sawActive = false
        while (System.nanoTime() < deadline)
        {
            val active = systemctlIsActive(unit)
            if (active == "active")
            {
                sawActive = true
                Thread.sleep(1_000)
                continue
            }
            if (sawActive && (active == "failed" || active == "inactive" || active == "deactivating"))
            {
                return false
            }
            Thread.sleep(1_000)
        }
        return systemctlIsActive(unit) == "active"
    }

    private fun systemctlIsActive(unit: String): String =
        runCatching {
            val pb = ProcessBuilder("systemctl", "is-active", unit)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out
        }.getOrDefault("")

    private fun fail(
        nodeId: String,
        nodeDir: Path,
        network: String,
        env: String,
        logFile: String?,
        error: String,
        message: String,
    )
    {
        val previous = ClientStagingLayout.readPreviousVersion(nodeDir)
            .ifEmpty { state.get(nodeId).previousVersion }
        state.update(nodeId) {
            it.copy(
                phase = "error",
                step = it.step.ifEmpty { "updating" },
                detail = message,
                lastError = "$error: $message",
                pct = 100,
                previousVersion = previous.ifEmpty { it.previousVersion },
                logTail = readLogTail(nodeDir, logFile),
            )
        }
        report(nodeId, eventId = "error", eventLabel = "Failed")
        runCatching { HostNodeLaunchSupport.stopUnit(network, env, nodeDir) }
    }

    private fun patch(nodeId: String, transform: (ClientUpdateSnapshot) -> ClientUpdateSnapshot)
    {
        state.update(nodeId, transform)
    }

    private fun report(nodeId: String, eventId: String = "", eventLabel: String = "")
    {
        val snap = state.get(nodeId)
        scope.launch(Dispatchers.IO) {
            val enrolled = enrollment.read() ?: return@launch
            val panelUrl = enrolled.panelUrl.trim().trimEnd('/')
            if (panelUrl.isEmpty() || enrolled.serverId.isBlank() || agentToken.isBlank())
            {
                return@launch
            }
            runCatching {
                notifyPanel(
                    panelUrl = panelUrl,
                    token = agentToken,
                    serverId = enrolled.serverId,
                    nodeId = nodeId,
                    phase = snap.phase,
                    step = snap.step,
                    detail = snap.detail,
                    pct = snap.pct,
                    local = snap.local,
                    latest = snap.latest,
                    previousVersion = snap.previousVersion,
                    updateAvailable = snap.updateAvailable,
                    lastError = snap.lastError,
                    logTail = snap.logTail,
                    eventId = eventId.ifEmpty { snap.step },
                    eventLabel = eventLabel.ifEmpty { snap.step },
                )
            }
        }
    }

    private fun readLogTail(nodeDir: Path, logFile: String?): String
    {
        val relative = logFile?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching {
                val launch = nodeDir.resolve(".toolkit/launch.json")
                if (!Files.isRegularFile(launch))
                {
                    return@runCatching null
                }
                val text = Files.readString(launch)
                Regex(""""log_file"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.getOrNull(1)
            }.getOrNull()
            ?: "logs/node.out"
        val path = nodeDir.resolve(relative).normalize()
        if (!path.startsWith(nodeDir.normalize()) || !Files.isRegularFile(path))
        {
            return ""
        }
        return runCatching {
            Files.readAllLines(path).takeLast(logTailLines).joinToString("\n")
        }.getOrDefault("")
    }
}
