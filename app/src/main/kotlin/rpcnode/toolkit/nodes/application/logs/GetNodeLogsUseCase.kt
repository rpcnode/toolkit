package rpcnode.toolkit.nodes.application.logs

import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.application.snapshot.ResolveSnapshotDestDirUseCase
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

data class NodeLogsView(
    val nodeId: String,
    val path: String,
    val lines: List<String>,
    val truncated: Boolean,
)

sealed interface GetNodeLogsResult
{
    data class Ok(val view: NodeLogsView) : GetNodeLogsResult
    data object NotFound : GetNodeLogsResult
    data object ServerNotFound : GetNodeLogsResult
    data object AgentUnreachable : GetNodeLogsResult
    data object InvalidAgentKey : GetNodeLogsResult
    /** Node is known but the host has no log file yet. */
    data object Unavailable : GetNodeLogsResult
}

/**
 * Panel → host agent: tail of the chain process log.
 * Path from `clients/<network>.yml` → `requirements.logFile` under the node dest dir
 * (e.g. `…/litefullnode/logs/tron.log`); fallback on the agent is `logs/node.out`.
 */
class GetNodeLogsUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val facts: NetworkFactsRepository,
    private val catalog: ClientProgramCatalog,
    private val resolveDestDir: ResolveSnapshotDestDirUseCase,
    private val fetchOnHost: FetchNodeLogsOnHost,
    private val defaultLines: Int = 200,
    private val maxLines: Int = 2000,
)
{
    suspend operator fun invoke(
        idRaw: String,
        linesRaw: Int?,
        logFileOverride: String? = null,
    ): GetNodeLogsResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return GetNodeLogsResult.NotFound
        val node = nodes.findById(id) ?: return GetNodeLogsResult.NotFound
        val server = servers.find(node.serverId) ?: return GetNodeLogsResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return GetNodeLogsResult.AgentUnreachable
        }
        val nodeDir = resolveDestDir(node)?.trim()?.takeIf { it.isNotEmpty() }
        val override = logFileOverride?.trim()?.takeIf { it.isNotEmpty() }
        val program = facts.factsFor(node.network)?.clientConfig?.program
        val logFile = override ?: if (program != null)
        {
            catalog.programsFor(node.network, node.env)
                .firstOrNull { it.programId.equals(program, ignoreCase = true) }
                ?.requirements
                ?.logFile
        }
        else
        {
            null
        }
        val lines = (linesRaw ?: defaultLines).coerceIn(1, maxLines)
        return when (
            val host = fetchOnHost.logs(
                agentUrl = agentUrl,
                token = agentKey,
                nodeId = node.id.value,
                lines = lines,
                nodeDir = nodeDir,
                logFile = logFile,
            )
        )
        {
            is FetchNodeLogsResult.Ok ->
                GetNodeLogsResult.Ok(
                    NodeLogsView(
                        nodeId = node.id.value,
                        path = host.logs.path,
                        lines = host.logs.lines,
                        truncated = host.logs.truncated,
                    ),
                )
            FetchNodeLogsResult.Empty -> GetNodeLogsResult.Unavailable
            FetchNodeLogsResult.Unauthorized -> GetNodeLogsResult.InvalidAgentKey
            FetchNodeLogsResult.Unreachable -> GetNodeLogsResult.AgentUnreachable
        }
    }
}
