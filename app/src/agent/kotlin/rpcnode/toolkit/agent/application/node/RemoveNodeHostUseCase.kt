package rpcnode.toolkit.agent.application.node

import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.agent.infrastructure.proc.runningAsRoot
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

sealed interface RemoveNodeHostResult
{
    data object Removed : RemoveNodeHostResult
    data object NotRoot : RemoveNodeHostResult
    data object NotFound : RemoveNodeHostResult
    data class Failed(val detail: String) : RemoveNodeHostResult
}

/**
 * Host side of panel node remove (agents / wipe):
 * stop+delete systemd unit; optionally wipe [nodeDir] contents; drop running registry.
 */
class RemoveNodeHostUseCase(
    private val registry: RunningNodeRegistry,
    private val isRoot: () -> Boolean = ::runningAsRoot,
)
{
    suspend operator fun invoke(
        nodeIdRaw: String,
        networkRaw: String,
        envRaw: String,
        nodeDirRaw: String?,
        wipeData: Boolean,
    ): RemoveNodeHostResult
    {
        if (!isRoot())
        {
            return RemoveNodeHostResult.NotRoot
        }
        val nodeId = nodeIdRaw.trim()
        if (nodeId.isEmpty())
        {
            return RemoveNodeHostResult.NotFound
        }
        val existing = registry.get(nodeId)
        val network = networkRaw.trim().lowercase().ifEmpty { existing?.network.orEmpty() }
        val env = envRaw.trim().lowercase().ifEmpty { existing?.env.orEmpty() }
        if (network.isEmpty() || env.isEmpty())
        {
            return RemoveNodeHostResult.Failed("network and env required to remove systemd unit")
        }
        val nodeDir = sanitizeNodeDir(nodeDirRaw) ?: sanitizeNodeDir(existing?.nodeDir)
        return withContext(Dispatchers.IO) {
            when (val removed = HostNodeLaunchSupport.removeUnit(network, env, nodeDir?.let { Path.of(it) }))
            {
                is HostNodeStartResult.Failed -> return@withContext RemoveNodeHostResult.Failed(removed.detail)
                HostNodeStartResult.InvalidLaunch ->
                    return@withContext RemoveNodeHostResult.Failed("invalid launch")
                is HostNodeStartResult.Pending ->
                    return@withContext RemoveNodeHostResult.Failed(removed.detail)
                is HostNodeStartResult.Started -> Unit
            }
            if (wipeData)
            {
                if (nodeDir == null)
                {
                    return@withContext RemoveNodeHostResult.Failed(
                        "wipe requested but node_dir is missing — save disk layout / Start first",
                    )
                }
                when (val wiped = HostNodeLaunchSupport.wipeNodeDir(Path.of(nodeDir)))
                {
                    is HostNodeStartResult.Failed -> return@withContext RemoveNodeHostResult.Failed(wiped.detail)
                    HostNodeStartResult.InvalidLaunch ->
                        return@withContext RemoveNodeHostResult.Failed("wipe failed")
                    is HostNodeStartResult.Pending ->
                        return@withContext RemoveNodeHostResult.Failed(wiped.detail)
                    is HostNodeStartResult.Started -> Unit
                }
            }
            registry.remove(nodeId)
            RemoveNodeHostResult.Removed
        }
    }

    private fun sanitizeNodeDir(raw: String?): String?
    {
        val dir = raw?.trim().orEmpty()
        if (dir.isEmpty() || !dir.startsWith("/") || ".." in dir)
        {
            return null
        }
        return dir
    }
}
