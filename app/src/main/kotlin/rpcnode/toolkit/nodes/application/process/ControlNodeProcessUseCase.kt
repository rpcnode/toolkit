package rpcnode.toolkit.nodes.application.process

import java.time.Instant
import rpcnode.toolkit.nodes.application.start.StartNodeResult
import rpcnode.toolkit.nodes.application.start.StartNodeUseCase
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface ControlNodeProcessResult
{
    data class Ok(val nodeId: String, val pid: Long, val action: String) : ControlNodeProcessResult
    data object NotFound : ControlNodeProcessResult
    data object ServerNotFound : ControlNodeProcessResult
    data object AgentUnreachable : ControlNodeProcessResult
    data object InvalidAgentKey : ControlNodeProcessResult
    data class Failed(val error: String, val message: String) : ControlNodeProcessResult
}

/**
 * Sync UI process control.
 * Stop → host `systemctl stop`, status [NodeStatus.STOPPED].
 * Start → full [StartNodeUseCase] (chain recipe + network systemd template + restart),
 * so missing units / missing `.toolkit/launch.json` are repaired automatically.
 */
class ControlNodeProcessUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val controlOnHost: ControlNodeProcessOnHost,
    private val startNode: StartNodeUseCase,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend fun stop(idRaw: String): ControlNodeProcessResult = stopOnly(idRaw)

    suspend fun start(idRaw: String): ControlNodeProcessResult = fullStart(idRaw)

    private suspend fun stopOnly(idRaw: String): ControlNodeProcessResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return ControlNodeProcessResult.NotFound
        val node = nodes.findById(id) ?: return ControlNodeProcessResult.NotFound
        val server = servers.find(node.serverId) ?: return ControlNodeProcessResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return ControlNodeProcessResult.AgentUnreachable
        }
        val host = controlOnHost.control(
            agentUrl,
            agentKey,
            node.id.value,
            node.network.value,
            node.env.value,
            "stop",
        ) ?: return ControlNodeProcessResult.AgentUnreachable
        if (!host.ok)
        {
            val error = host.error.ifBlank { "failed" }
            if (error == "invalid_agent_key" || error == "agent_unauthorized" || error == "unauthorized")
            {
                return ControlNodeProcessResult.InvalidAgentKey
            }
            return ControlNodeProcessResult.Failed(
                error = error,
                message = host.message,
            )
        }
        nodes.updateStatus(id, NodeStatus.STOPPED, clock())
        return ControlNodeProcessResult.Ok(
            nodeId = node.id.value,
            pid = host.pid,
            action = host.action.ifBlank { "stop" },
        )
    }

    private suspend fun fullStart(idRaw: String): ControlNodeProcessResult
    {
        return when (val result = startNode(idRaw, installOptionsJson = null))
        {
            is StartNodeResult.Started ->
                ControlNodeProcessResult.Ok(
                    nodeId = result.nodeId,
                    pid = result.pid,
                    action = "start",
                )
            StartNodeResult.NotFound -> ControlNodeProcessResult.NotFound
            StartNodeResult.ServerNotFound -> ControlNodeProcessResult.ServerNotFound
            is StartNodeResult.AgentUnreachable -> ControlNodeProcessResult.AgentUnreachable
            StartNodeResult.NoClientConfig ->
                ControlNodeProcessResult.Failed("no_client_config", "Network has no client config")
            StartNodeResult.NoDiskLayout ->
                ControlNodeProcessResult.Failed("no_disk_layout", "Disk layout is not saved yet")
            StartNodeResult.TemplateMissing ->
                ControlNodeProcessResult.Failed("template_missing", "Client config template is missing")
            StartNodeResult.InvalidType ->
                ControlNodeProcessResult.Failed("invalid_type", "Invalid install options")
            StartNodeResult.UnsupportedNetwork ->
                ControlNodeProcessResult.Failed("unsupported_network", "No start recipe for this network")
            is StartNodeResult.SyncFailed ->
                ControlNodeProcessResult.Failed(result.error, result.message)
            is StartNodeResult.BuildPending ->
                ControlNodeProcessResult.Failed(
                    result.error.ifBlank { "client_build_pending" },
                    result.message,
                )
            is StartNodeResult.StartFailed ->
            {
                val error = result.error.ifBlank { "start_failed" }
                if (error == "invalid_agent_key" || error == "unauthorized" || error == "not_root")
                {
                    if (error == "invalid_agent_key" || error == "unauthorized")
                    {
                        ControlNodeProcessResult.InvalidAgentKey
                    }
                    else
                    {
                        ControlNodeProcessResult.Failed(error, result.message)
                    }
                }
                else
                {
                    ControlNodeProcessResult.Failed(error, result.message)
                }
            }
        }
    }
}
