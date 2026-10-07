package rpcnode.toolkit.nodes.application.test

import java.time.Instant
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.application.config.clientConfigTemplateName
import rpcnode.toolkit.nodes.application.snapshot.ResolveSnapshotDestDirUseCase
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.repository.ServerRepository

data class NodeLiveTestCheck(
    val id: String,
    val title: String,
    val ok: Boolean,
    val detail: String = "",
    val error: String = "",
)

data class NodeLiveTestReport(
    val ok: Boolean,
    val network: String,
    val env: String,
    val error: String,
    val checks: List<NodeLiveTestCheck>,
    val at: String,
)

data class TestNodeOnHostCommand(
    val nodeId: String,
    val network: String,
    val env: String,
    val nodeDir: String,
    val httpPort: Int,
    val configFile: String,
)

data class TestNodeOnHostResult(
    val ok: Boolean,
    val checks: List<NodeLiveTestCheck> = emptyList(),
    val error: String = "",
    val message: String = "",
)

fun interface TestNodeOnHost
{
    /** POST /api/v1/node/test on the host agent. Null = agent unreachable. */
    suspend fun test(agentUrl: String, token: String, command: TestNodeOnHostCommand): TestNodeOnHostResult?
}

sealed interface RunNodeLiveTestResult
{
    data class Done(val report: NodeLiveTestReport) : RunNodeLiveTestResult
    data object NotFound : RunNodeLiveTestResult
    data object ServerNotFound : RunNodeLiveTestResult
    data object NoDiskLayout : RunNodeLiveTestResult
    data object InvalidAgentKey : RunNodeLiveTestResult
    data class AgentUnreachable(val detail: String = "") : RunNodeLiveTestResult
}

/**
 * Node page "Test": asks the host agent for a read-only live check (process → port → chain RPC
 * height), adds how far the node is from the public tip, and stores pass/fail on the node.
 * Never starts, stops or wipes anything.
 */
class RunNodeLiveTestUseCase(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val facts: NetworkFactsRepository,
    private val catalog: ClientProgramCatalog,
    private val resolveDestDir: ResolveSnapshotDestDirUseCase,
    private val chainStarts: Map<NetworkId, ChainNodeStart>,
    private val testOnHost: TestNodeOnHost,
    private val publicTip: suspend (NetworkId, EnvId) -> Long?,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    /** Looks the node up by [nodeId], or by server + network + env when the id is not given. */
    suspend operator fun invoke(
        nodeId: String?,
        serverId: String? = null,
        network: String? = null,
        env: String? = null,
    ): RunNodeLiveTestResult
    {
        val node = find(nodeId, serverId, network, env) ?: return RunNodeLiveTestResult.NotFound
        val server = servers.find(node.serverId) ?: return RunNodeLiveTestResult.ServerNotFound
        val agentUrl = server.agentUrl.trim()
        val agentKey = server.agentKey.trim()
        if (agentUrl.isEmpty() || agentKey.isEmpty())
        {
            return RunNodeLiveTestResult.AgentUnreachable("missing agent url or key")
        }
        val nodeDir = resolveDestDir(node)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return RunNodeLiveTestResult.NoDiskLayout

        val clientConfig = facts.factsFor(node.network)?.clientConfig
        val program = clientConfig?.program.orEmpty()
        val programSpec = catalog.programsFor(node.network, node.env)
            .firstOrNull { it.programId.equals(program, ignoreCase = true) }
        val configFile = clientConfig?.let { clientConfigTemplateName(it, node.env.value) }
        val plan = chainStarts[node.network]?.plan(
            ChainNodeStartContext(
                network = node.network,
                env = node.env.value,
                program = program,
                configFile = configFile,
                nodeDir = nodeDir,
                javaMajor = programSpec?.requirements?.javaMajor,
                logFile = programSpec?.requirements?.logFile,
                installOptionsJson = node.installOptionsJson,
                diskLayoutJson = node.diskLayoutJson,
            ),
        )
        val httpPort = plan
            ?.let { p ->
                catalog.programsFor(node.network, node.env).flatMap { it.ports }
                    .firstOrNull { it.role.equals(p.height.portRole, ignoreCase = true) }?.port
            }
            ?: node.nodeHttpPort.takeIf { it > 0 }
            ?: 0

        val host = testOnHost.test(
            agentUrl,
            agentKey,
            TestNodeOnHostCommand(
                nodeId = node.id.value,
                network = node.network.value,
                env = node.env.value,
                nodeDir = nodeDir,
                httpPort = httpPort,
                configFile = configFile.orEmpty(),
            ),
        ) ?: return RunNodeLiveTestResult.AgentUnreachable()
        if (host.error == "invalid_agent_key" || host.error == "unauthorized")
        {
            return RunNodeLiveTestResult.InvalidAgentKey
        }
        if (host.checks.isEmpty() && !host.ok)
        {
            // Old agent without /node/test, or a host-side failure before any check ran.
            return RunNodeLiveTestResult.AgentUnreachable(
                host.message.ifBlank { host.error.ifBlank { "agent did not run the node test — update the agent" } },
            )
        }

        val checks = host.checks.toMutableList()
        val rpcPassed = checks.any { it.id == "rpc" && it.ok }
        if (rpcPassed)
        {
            checks += tipCheck(node, checks.first { it.id == "rpc" })
        }
        val ok = checks.isNotEmpty() && checks.all { it.ok }
        val firstFailure = checks.firstOrNull { !it.ok }
        val error = if (ok) "" else (firstFailure?.let { "${it.title}: ${it.error.ifBlank { it.detail }}" }.orEmpty())
        val at = clock()
        nodes.saveLiveTest(node.id, status = if (ok) "pass" else "fail", at = at, error = error, updatedAt = at)
        return RunNodeLiveTestResult.Done(
            NodeLiveTestReport(
                ok = ok,
                network = node.network.value,
                env = node.env.value,
                error = error,
                checks = checks,
                at = at,
            ),
        )
    }

    /** Informational: lag behind the public tip never fails the test (a syncing node is alive). */
    private suspend fun tipCheck(node: Node, rpc: NodeLiveTestCheck): NodeLiveTestCheck
    {
        val height = Regex("""height=(\d+)""").find(rpc.detail)?.groupValues?.get(1)?.toLongOrNull()
        val tip = publicTip(node.network, node.env)?.takeIf { it > 0 }
        return when
        {
            height == null || tip == null ->
                NodeLiveTestCheck("tip", "Public tip", ok = true, detail = "tip unavailable — skipped")
            height >= tip - TIP_LAG_OK ->
                NodeLiveTestCheck("tip", "Public tip", ok = true, detail = "at tip ($height / $tip)")
            else ->
                NodeLiveTestCheck("tip", "Public tip", ok = true, detail = "syncing: ${tip - height} blocks behind ($height / $tip)")
        }
    }

    private suspend fun find(nodeId: String?, serverId: String?, network: String?, env: String?): Node?
    {
        nodeId?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            return NodeId.parse(raw)?.let { nodes.findById(it) }
        }
        val sid = serverId?.trim()?.takeIf { it.isNotEmpty() }?.let { ServerId.parse(it) } ?: return null
        val net = network?.trim()?.takeIf { it.isNotEmpty() }?.let { NetworkId.parse(it) } ?: return null
        val e = env?.trim()?.takeIf { it.isNotEmpty() }?.let { EnvId.parse(it) } ?: return null
        return nodes.findByServerNetworkEnv(sid, net, e)
    }

    private companion object
    {
        const val TIP_LAG_OK = 3L
    }
}
