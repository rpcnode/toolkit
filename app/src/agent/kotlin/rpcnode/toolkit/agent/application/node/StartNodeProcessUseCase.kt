package rpcnode.toolkit.agent.application.node

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.application.enroll.PanelEnrollmentStore
import rpcnode.toolkit.agent.domain.model.RunningNode
import rpcnode.toolkit.agent.infrastructure.node.resolveHostClientVersion
import rpcnode.toolkit.agent.infrastructure.proc.runningAsRoot
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Starts a node via systemd (chain runtime writes the unit), persists MainPID,
 * registers for height polling, then notifies the panel. Requires root.
 */
class StartNodeProcessUseCase(
    private val runtimes: Map<String, ChainNodeRuntime>,
    private val registry: RunningNodeRegistry,
    private val enrollment: PanelEnrollmentStore,
    private val notifyStarted: NotifyPanelNodeStarted,
    private val agentToken: String,
    private val scope: CoroutineScope,
    private val writePid: (Path, Long) -> Unit = ::writeNodePid,
    private val isRoot: () -> Boolean = ::runningAsRoot,
)
{
    private val log = LoggerFactory.getLogger(StartNodeProcessUseCase::class.java)

    suspend operator fun invoke(command: NodeStartCommand): NodeStartProcessResult
    {
        if (!isRoot())
        {
            return NodeStartProcessResult.NotRoot
        }
        val nodeDir = command.nodeDir.trim()
        if (nodeDir.isEmpty() || !nodeDir.startsWith("/") || nodeDir.contains(".."))
        {
            return NodeStartProcessResult.InvalidNodeDir
        }
        if (command.launch.kind.isBlank() || command.launch.entry.isBlank())
        {
            return NodeStartProcessResult.InvalidLaunch
        }
        val network = command.network.trim().lowercase()
        val runtime = runtimes[network] ?: return NodeStartProcessResult.UnsupportedNetwork

        return withContext(Dispatchers.IO) {
            val dest = Path.of(nodeDir)
            val networkName = command.network.trim().lowercase()
            val envName = command.env.trim().lowercase()
            val unit = HostNodeLaunchSupport.unitName(networkName, envName)
            val existingPid = HostNodeLaunchSupport.mainPid(unit)
            if (existingPid != null && processAlive(existingPid))
            {
                val version = resolveClientVersion(command)
                register(command, existingPid, version)
                notifyAsync(command.nodeId, existingPid, version)
                return@withContext NodeStartProcessResult.Started(pid = existingPid, alreadyRunning = true)
            }

            when (
                val started = runtime.starter.start(
                    command.nodeId,
                    networkName,
                    envName,
                    command.nodeDir,
                    command.launch.toSpec(),
                )
            )
            {
                is HostNodeStartResult.Started ->
                {
                    writePid(dest, started.pid)
                    val version = resolveClientVersion(command)
                    register(command, started.pid, version)
                    notifyAsync(command.nodeId, started.pid, version)
                    NodeStartProcessResult.Started(pid = started.pid)
                }
                HostNodeStartResult.InvalidLaunch ->
                    NodeStartProcessResult.InvalidLaunch
                is HostNodeStartResult.Failed ->
                    NodeStartProcessResult.Failed(started.detail)
                is HostNodeStartResult.Pending ->
                    NodeStartProcessResult.Pending(started.detail)
            }
        }
    }

    private fun resolveClientVersion(command: NodeStartCommand): String =
        resolveHostClientVersion(command.nodeDir, seed = command.clientVersion)

    private fun register(command: NodeStartCommand, pid: Long, clientVersion: String)
    {
        registry.upsert(
            RunningNode(
                nodeId = command.nodeId.trim(),
                network = command.network.trim().lowercase(),
                env = command.env.trim().lowercase(),
                nodeDir = command.nodeDir.trim(),
                httpPort = command.httpPort,
                pid = pid,
                configFile = command.configFile.orEmpty(),
                program = command.program,
                heightKind = command.height.kind,
                logFile = command.launch.logFile.orEmpty(),
                clientVersion = clientVersion,
            ),
        )
    }

    private fun notifyAsync(nodeId: String, pid: Long, clientVersion: String)
    {
        scope.launch {
            try
            {
                val enrolled = enrollment.read() ?: return@launch
                notifyStarted(
                    panelUrl = enrolled.panelUrl,
                    token = agentToken,
                    serverId = enrolled.serverId,
                    nodeId = nodeId,
                    pid = pid,
                    clientVersion = clientVersion,
                )
            }
            catch (e: Exception)
            {
                log.warn("notify panel node started {}: {}", nodeId, e.message)
            }
        }
    }
}
fun writeNodePid(nodeDir: Path, pid: Long)
{
    val dir = nodeDir.resolve(".toolkit")
    Files.createDirectories(dir)
    Files.writeString(dir.resolve("node.pid"), "$pid\n")
}

fun processAlive(pid: Long): Boolean
{
    return try
    {
        ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }
    catch (_: Exception)
    {
        false
    }
}
