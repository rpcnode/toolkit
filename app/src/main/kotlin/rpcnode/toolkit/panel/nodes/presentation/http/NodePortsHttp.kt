package rpcnode.toolkit.panel.nodes.presentation.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rpcnode.toolkit.clients.domain.model.PortConfigPolicy
import rpcnode.toolkit.nodes.application.ports.NodePort
import rpcnode.toolkit.nodes.application.ports.NodePortsResult

/** One fixed catalog port; config is required, optional, or none (from clients YAML). */
@Serializable
data class NodePortItemResponse(
    val role: String,
    val port: Int,
    val label: String,
    val config: String = "required",
    @SerialName("config_enabled_default") val configEnabledDefault: Boolean = true,
    val free: Boolean? = null,
    val holder: String? = null,
)

@Serializable
data class NodePortsResponse(
    val ok: Boolean = true,
    val items: List<NodePortItemResponse> = emptyList(),
    val endpoint: String? = null,
    val error: String? = null,
    val message: String? = null,
)

suspend fun ApplicationCall.respondNodePorts(
    result: NodePortsResult,
    live: Boolean = false,
)
{
    when (result)
    {
        is NodePortsResult.Ok ->
            respond(
                NodePortsResponse(items = result.ports.map { it.toResponse() }, endpoint = result.endpoint),
            )
        is NodePortsResult.AgentUnreachable ->
            respond(
                NodePortsResponse(
                    ok = false,
                    items = result.ports.map { it.toResponse() },
                    endpoint = result.endpoint,
                    error = "agent_unreachable",
                    message = if (live)
                    {
                        "Host agent did not answer — could not check ports"
                    }
                    else
                    {
                        "Host agent did not answer — showing catalog ports without live status"
                    },
                ),
            )
        NodePortsResult.NoPorts ->
            respond(
                NodePortsResponse(message = "This network/env has no fixed ports to check"),
            )
        NodePortsResult.NotFound ->
            respond(HttpStatusCode.NotFound, NodePortsResponse(ok = false, error = "not_found"))
        NodePortsResult.ServerNotFound ->
            respond(
                HttpStatusCode.BadRequest,
                NodePortsResponse(ok = false, error = "server_not_found"),
            )
    }
}

private fun NodePort.toResponse() = NodePortItemResponse(
    role = role,
    port = port,
    label = label,
    config = configPolicy.configPolicyName(),
    configEnabledDefault = configPolicy == PortConfigPolicy.REQUIRED,
    free = free,
    holder = holder,
)

private fun PortConfigPolicy.configPolicyName(): String = when (this)
{
    PortConfigPolicy.REQUIRED -> "required"
    PortConfigPolicy.OPTIONAL -> "optional"
    PortConfigPolicy.NONE -> "none"
}
