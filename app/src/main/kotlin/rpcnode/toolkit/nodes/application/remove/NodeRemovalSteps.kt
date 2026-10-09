package rpcnode.toolkit.nodes.application.remove

import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

data class RemovalStepView(
    val id: String,
    val title: String,
    /** pending | running | done | skipped | failed */
    val status: String,
    val detail: String,
)

data class NodeRemovalView(
    val nodeId: String,
    val steps: List<RemovalStepView>,
    val done: Boolean,
    val failed: Boolean,
    val error: String,
)

data class StartRemovalOnHostCommand(
    val nodeId: String,
    val network: String,
    val env: String,
    val dirs: List<String>,
    val wipeData: Boolean,
)

sealed interface RemovalOnHostResult
{
    data class Ok(val view: NodeRemovalView) : RemovalOnHostResult

    /** The agent has no removal job for this node (restarted, or never started). */
    data object NoJob : RemovalOnHostResult
    data class Failed(val error: String, val message: String) : RemovalOnHostResult
}

/** Panel → host agent: start the removal job and poll its steps. `null` = agent did not answer. */
interface NodeRemovalOnHost
{
    suspend fun start(agentUrl: String, token: String, command: StartRemovalOnHostCommand): RemovalOnHostResult?
    suspend fun progress(agentUrl: String, token: String, nodeId: String): RemovalOnHostResult?
}

sealed interface NodeRemovalResult
{
    data class Ok(val view: NodeRemovalView) : NodeRemovalResult
    data object NotFound : NodeRemovalResult
    data object ServerNotFound : NodeRemovalResult
    data object AgentUnreachable : NodeRemovalResult
    data object InvalidAgentKey : NodeRemovalResult
    data class Failed(val error: String, val message: String) : NodeRemovalResult
}

/**
 * Every folder that can hold this node's data: all disk-layout roles, the snapshot destination and its
 * `fullnode` / `litefullnode` siblings (Lite snapshots land in a different leaf than the layout role).
 */
fun nodeRemovalDirs(node: Node, destDir: String?): List<String>
{
    val dirs = linkedSetOf<String>()
    fun add(raw: String?)
    {
        val dir = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return
        dirs += dir
    }
    decodeNodeDiskLayout(node.diskLayoutJson)?.let { layout ->
        layout.roles.forEach { add(it.dir) }
        add(layout.ledgerDir)
        add(layout.accountsDir)
        add(layout.snapshotsDir)
        add(layout.stateDir)
        add(layout.indexDir)
    }
    add(destDir)
    for (base in listOfNotNull(destDir))
    {
        val trimmed = base.trim().trimEnd('/')
        val parent = trimmed.substringBeforeLast('/', "")
        if (parent.isNotEmpty())
        {
            add("$parent/fullnode")
            add("$parent/litefullnode")
        }
    }
    return dirs.toList()
}

/**
 * Starts the step-by-step removal (stop → delete files → delete service) and reports its progress.
 * `panel` mode drops the row immediately; otherwise the row goes once the host reports "done".
 */
class NodeRemovalSteps(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val resolveDestDir: suspend (Node) -> String?,
    private val host: NodeRemovalOnHost,
)
{
    suspend fun start(idRaw: String, mode: RemoveNodeMode): NodeRemovalResult
    {
        val id = NodeId.parse(idRaw) ?: return NodeRemovalResult.NotFound
        val node = nodes.findById(id) ?: return NodeRemovalResult.NotFound
        if (mode == RemoveNodeMode.PANEL)
        {
            nodes.delete(id)
            return NodeRemovalResult.Ok(
                NodeRemovalView(
                    nodeId = id.value,
                    steps = listOf(RemovalStepView("panel", "Remove from the panel", "done", "the host was not changed")),
                    done = true,
                    failed = false,
                    error = "",
                ),
            )
        }
        val (agentUrl, agentKey) = credentials(node) ?: return missingServer(node)
        val dirs = nodeRemovalDirs(node, resolveDestDir(node))
        val result = host.start(
            agentUrl,
            agentKey,
            StartRemovalOnHostCommand(
                nodeId = node.id.value,
                network = node.network.value,
                env = node.env.value,
                dirs = dirs,
                wipeData = mode == RemoveNodeMode.WIPE,
            ),
        )
        return finish(node, result)
    }

    suspend fun progress(idRaw: String): NodeRemovalResult
    {
        val id = NodeId.parse(idRaw) ?: return NodeRemovalResult.NotFound
        val node = nodes.findById(id) ?: return NodeRemovalResult.NotFound
        val (agentUrl, agentKey) = credentials(node) ?: return missingServer(node)
        return finish(node, host.progress(agentUrl, agentKey, node.id.value))
    }

    private suspend fun finish(node: Node, result: RemovalOnHostResult?): NodeRemovalResult =
        when (result)
        {
            null -> NodeRemovalResult.AgentUnreachable
            RemovalOnHostResult.NoJob ->
                NodeRemovalResult.Failed("no_removal_job", "The host has no removal job for this node — start it again")
            is RemovalOnHostResult.Failed ->
                if (result.error == "invalid_agent_key" || result.error == "unauthorized")
                {
                    NodeRemovalResult.InvalidAgentKey
                }
                else
                {
                    NodeRemovalResult.Failed(result.error, result.message)
                }
            is RemovalOnHostResult.Ok ->
            {
                if (result.view.done)
                {
                    nodes.delete(node.id)
                }
                NodeRemovalResult.Ok(result.view)
            }
        }

    private suspend fun credentials(node: Node): Pair<String, String>?
    {
        val server = servers.find(node.serverId) ?: return null
        val url = server.agentUrl.trim()
        val key = server.agentKey.trim()
        return if (url.isBlank() || key.isBlank()) null else Pair(url, key)
    }

    private suspend fun missingServer(node: Node): NodeRemovalResult =
        if (servers.find(node.serverId) == null) NodeRemovalResult.ServerNotFound else NodeRemovalResult.AgentUnreachable
}
