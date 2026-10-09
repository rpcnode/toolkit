package rpcnode.toolkit.nodes.application.ports

import java.net.URI
import rpcnode.toolkit.clients.domain.model.PortConfigPolicy
import rpcnode.toolkit.clients.domain.model.ProgramPort
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.repository.ServerRepository

/** One catalog port merged with its live agent status, when the agent answered. */
data class NodePort(
    val role: String,
    val port: Int,
    val label: String,
    val configPolicy: PortConfigPolicy = PortConfigPolicy.REQUIRED,
    val free: Boolean? = null,
    val holder: String? = null,
)

sealed interface NodePortsResult
{
    /** [endpoint] is the primary rpc/http port of the node, host resolved from the server's agent URL. */
    data class Ok(val ports: List<NodePort>, val endpoint: String? = null) : NodePortsResult
    data object NotFound : NodePortsResult
    data object ServerNotFound : NodePortsResult
    /** This network/env ships no fixed ports (nothing for "check ports" to do). */
    data object NoPorts : NodePortsResult
    /** Catalog ports are known, but the host agent did not answer — free/holder stay unset. */
    data class AgentUnreachable(val ports: List<NodePort>, val endpoint: String? = null) : NodePortsResult
}

/** @deprecated alias — live check use case returns this shape. */
typealias CheckNodePortsResult = NodePortsResult

private val ENDPOINT_ROLE_PRIORITY = listOf("rpc")

internal class NodePortsCatalogResolver(
    private val nodes: NodeRepository,
    private val servers: ServerRepository,
    private val catalog: ClientProgramCatalog,
)
{
    data class AgentTarget(val agentUrl: String, val agentKey: String)

    sealed interface Resolved
    {
        data class Ready(
            val catalogPorts: List<ProgramPort>,
            val endpoint: String?,
        ) : Resolved

        data object NotFound : Resolved
        data object ServerNotFound : Resolved
        data object NoPorts : Resolved
    }

    suspend fun resolve(idRaw: String): Resolved
    {
        val id = NodeId.parse(idRaw) ?: return Resolved.NotFound
        val node = nodes.findById(id) ?: return Resolved.NotFound
        val server = servers.find(node.serverId) ?: return Resolved.ServerNotFound
        return resolve(node, server)
    }

    suspend fun agentFor(idRaw: String): AgentTarget?
    {
        val id = NodeId.parse(idRaw) ?: return null
        val node = nodes.findById(id) ?: return null
        val server = servers.find(node.serverId) ?: return null
        return AgentTarget(agentUrl = server.agentUrl, agentKey = server.agentKey)
    }

    fun resolve(node: Node, server: Server): Resolved
    {
        val catalogPorts = catalog.programsFor(node.network, node.env).flatMap { it.ports }
        if (catalogPorts.isEmpty())
        {
            return Resolved.NoPorts
        }
        return Resolved.Ready(
            catalogPorts = catalogPorts,
            endpoint = endpointFor(server.agentUrl, catalogPorts),
        )
    }
}

internal fun endpointFor(agentUrl: String, ports: List<ProgramPort>): String?
{
    val primary = ENDPOINT_ROLE_PRIORITY.firstNotNullOfOrNull { role -> ports.firstOrNull { it.role == role } }
        ?: ports.firstOrNull { it.role.startsWith("http") }
        ?: return null
    val host = runCatching { URI(agentUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return "http://$host:${primary.port}"
}

internal fun ProgramPort.toNodePort(free: Boolean? = null, holder: String? = null) =
    NodePort(role = role, port = port, label = label, configPolicy = configPolicy, free = free, holder = holder)

internal fun List<ProgramPort>.toCatalogNodePorts() = map { it.toNodePort() }
