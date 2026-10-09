package rpcnode.toolkit.agent.application.node

import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.agent.infrastructure.proc.runningAsRoot
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

sealed interface ControlNodeUnitResult
{
    data class Ok(val pid: Long, val action: String) : ControlNodeUnitResult
    data object NotRoot : ControlNodeUnitResult
    data object NotFound : ControlNodeUnitResult
    data class Failed(val detail: String) : ControlNodeUnitResult
}

/** Stop / start an already-installed `rpcnode-<network>-<env>.service` (Sync UI).
 *  Start always re-applies the network systemd template when `.toolkit/launch.json` exists.
 */
class ControlNodeUnitUseCase(
    private val registry: RunningNodeRegistry,
    private val isRoot: () -> Boolean = ::runningAsRoot,
)
{
    suspend fun stop(
        nodeIdRaw: String,
        networkRaw: String = "",
        envRaw: String = "",
    ): ControlNodeUnitResult = control(nodeIdRaw, networkRaw, envRaw, stop = true)

    suspend fun start(
        nodeIdRaw: String,
        networkRaw: String = "",
        envRaw: String = "",
    ): ControlNodeUnitResult = control(nodeIdRaw, networkRaw, envRaw, stop = false)

    private suspend fun control(
        nodeIdRaw: String,
        networkRaw: String,
        envRaw: String,
        stop: Boolean,
    ): ControlNodeUnitResult
    {
        if (!isRoot())
        {
            return ControlNodeUnitResult.NotRoot
        }
        val nodeId = nodeIdRaw.trim()
        if (nodeId.isEmpty())
        {
            return ControlNodeUnitResult.NotFound
        }
        val existing = registry.get(nodeId)
        val network = networkRaw.trim().lowercase().ifEmpty { existing?.network.orEmpty() }
        val env = envRaw.trim().lowercase().ifEmpty { existing?.env.orEmpty() }
        if (network.isEmpty() || env.isEmpty())
        {
            return ControlNodeUnitResult.Failed("network and env required to resolve systemd unit")
        }
        val nodeDir = existing?.nodeDir?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        return withContext(Dispatchers.IO) {
            when (
                val result = if (stop)
                {
                    HostNodeLaunchSupport.stopUnit(network, env, nodeDir)
                }
                else
                {
                    HostNodeLaunchSupport.restartUnit(network, env, nodeDir)
                }
            )
            {
                is HostNodeStartResult.Started ->
                {
                    if (existing != null)
                    {
                        registry.upsert(existing.copy(pid = result.pid))
                    }
                    ControlNodeUnitResult.Ok(
                        pid = result.pid,
                        action = if (stop) "stop" else "start",
                    )
                }
                HostNodeStartResult.InvalidLaunch ->
                    ControlNodeUnitResult.Failed("invalid launch")
                is HostNodeStartResult.Failed ->
                    ControlNodeUnitResult.Failed(result.detail)
                is HostNodeStartResult.Pending ->
                    ControlNodeUnitResult.Failed(result.detail)
            }
        }
    }
}
