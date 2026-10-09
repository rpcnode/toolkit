package rpcnode.toolkit.nodes.application.hostdeps

import java.time.Instant
import java.util.UUID
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.repository.ServerRepository

class StartNodeHostDepsUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val buildPlan: BuildNodeHostDepsPlanUseCase,
    private val probeOnHost: ProbeHostDepsOnHost,
    private val startOnHost: StartHostDepsOnHost,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(idRaw: String): StartNodeHostDepsResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return StartNodeHostDepsResult.NotFound
        val node = nodes.findById(id) ?: return StartNodeHostDepsResult.NotFound
        val server = servers.find(node.serverId) ?: return StartNodeHostDepsResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return StartNodeHostDepsResult.AgentUnreachable
        }
        val plan = when (val got = buildPlan(id.value))
        {
            is GetNodeHostDepsPlanResult.Ok -> got.plan
            GetNodeHostDepsPlanResult.NotFound -> return StartNodeHostDepsResult.NotFound
        }
        if (plan.deps.isEmpty())
        {
            nodes.updateStatus(id, NodeStatus.parse("host_deps_complete"), clock())
            return StartNodeHostDepsResult.NothingToInstall
        }
        val specs = plan.deps.map {
            HostDepSpec(id = it.id, kind = it.kind, name = it.name, javaMajor = it.javaMajor)
        }
        val missing = when (val probed = probeOnHost.probe(agentUrl, agentKey, specs))
        {
            is ProbeHostDepsOnHostResult.Ok ->
            {
                val present = probed.items.filter { it.second }.map { it.first }.toSet()
                specs.filter { it.id !in present }
            }
            ProbeHostDepsOnHostResult.Unauthorized -> return StartNodeHostDepsResult.InvalidAgentKey
            ProbeHostDepsOnHostResult.Unreachable -> return StartNodeHostDepsResult.AgentUnreachable
        }
        if (missing.isEmpty())
        {
            nodes.updateStatus(id, NodeStatus.parse("host_deps_complete"), clock())
            return StartNodeHostDepsResult.NothingToInstall
        }
        val jobId = "hostdeps-${node.id.value}-${UUID.randomUUID().toString().take(8)}"
        return when (val started = startOnHost.start(agentUrl, agentKey, jobId, missing))
        {
            is StartHostDepsOnHostResult.Ok ->
            {
                nodes.updateStatus(id, NodeStatus.parse("host_deps_running"), clock())
                StartNodeHostDepsResult.Ok(
                    jobId = started.jobId,
                    deps = plan.deps.filter { d -> missing.any { it.id == d.id } },
                )
            }
            StartHostDepsOnHostResult.Unauthorized -> StartNodeHostDepsResult.InvalidAgentKey
            StartHostDepsOnHostResult.Unreachable -> StartNodeHostDepsResult.AgentUnreachable
        }
    }
}

class GetNodeHostDepsProgressUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val fetchOnHost: FetchHostDepsProgressOnHost,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    suspend operator fun invoke(idRaw: String, jobIdRaw: String): GetNodeHostDepsProgressResult
    {
        val id = NodeId.parse(idRaw.trim()) ?: return GetNodeHostDepsProgressResult.NotFound
        val node = nodes.findById(id) ?: return GetNodeHostDepsProgressResult.NotFound
        val server = servers.find(node.serverId) ?: return GetNodeHostDepsProgressResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isBlank() || agentKey.isBlank())
        {
            return GetNodeHostDepsProgressResult.AgentUnreachable
        }
        val jobId = jobIdRaw.trim()
        if (jobId.isEmpty())
        {
            return GetNodeHostDepsProgressResult.JobNotFound
        }
        return when (val got = fetchOnHost.progress(agentUrl, agentKey, jobId))
        {
            is FetchHostDepsProgressOnHostResult.Ok ->
            {
                if (got.progress.ready)
                {
                    nodes.updateStatus(id, NodeStatus.parse("host_deps_complete"), clock())
                }
                else if (got.progress.failed)
                {
                    nodes.updateStatus(id, NodeStatus.parse("host_deps_error"), clock())
                }
                GetNodeHostDepsProgressResult.Ok(got.progress)
            }
            FetchHostDepsProgressOnHostResult.JobNotFound -> GetNodeHostDepsProgressResult.JobNotFound
            FetchHostDepsProgressOnHostResult.Unauthorized -> GetNodeHostDepsProgressResult.InvalidAgentKey
            FetchHostDepsProgressOnHostResult.Unreachable -> GetNodeHostDepsProgressResult.AgentUnreachable
        }
    }
}
