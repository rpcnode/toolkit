package rpcnode.toolkit.panel.hosts.presentation.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.application.disks.GetHostDisksUseCase
import rpcnode.toolkit.nodes.application.disks.HostDisksResult
import rpcnode.toolkit.nodes.application.sysctl.GetHostSysctlUseCase
import rpcnode.toolkit.nodes.application.sysctl.HostSysctlResult
import rpcnode.toolkit.nodes.infrastructure.http.NodeHostDisksResponse
import rpcnode.toolkit.nodes.infrastructure.http.NodeHostSysctlResponse
import rpcnode.toolkit.nodes.infrastructure.http.toResponse
import rpcnode.toolkit.panel.nodes.presentation.http.NodePortsResponse
import rpcnode.toolkit.panel.nodes.presentation.http.respondNodePorts
import rpcnode.toolkit.wiring.Toolkit

fun Application.hostsApiRoutes(toolkit: Toolkit)
{
    routing {
        get("/api/host/disks") {
            val serverId = call.request.queryParameters["server_id"].orEmpty()
            when (val result = toolkit.getHostDisks(serverId))
            {
                is HostDisksResult.Ok ->
                    call.respond(
                        NodeHostDisksResponse(
                            disks = result.catalog.disks.map { it.toResponse() },
                            mounts = result.catalog.mounts.map { it.toResponse() },
                            unused = result.catalog.unused.map { it.toResponse() },
                            summary = result.summary,
                        ),
                    )
                HostDisksResult.ServerNotFound ->
                    call.respond(HttpStatusCode.BadRequest, NodeHostDisksResponse(ok = false, error = "server_not_found"))
                HostDisksResult.AgentUnreachable ->
                    call.respond(
                        NodeHostDisksResponse(
                            ok = false,
                            error = "agent_unreachable",
                            message = "Host agent did not answer — cannot read disk inventory",
                        ),
                    )
            }
        }

        get("/api/host/sysctl") {
            val serverId = call.request.queryParameters["server_id"].orEmpty()
            when (val result = toolkit.getHostSysctl(serverId))
            {
                is HostSysctlResult.Ok ->
                    call.respond(
                        NodeHostSysctlResponse(
                            current = result.catalog.current,
                            recommended = result.catalog.recommended,
                            installOptionKeys = result.catalog.installOptionKeys,
                        ),
                    )
                HostSysctlResult.ServerNotFound ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeHostSysctlResponse(ok = false, error = "server_not_found"),
                    )
                HostSysctlResult.AgentUnreachable ->
                    call.respond(
                        NodeHostSysctlResponse(
                            ok = false,
                            error = "agent_unreachable",
                            message = "Host agent did not answer — cannot read sysctl",
                        ),
                    )
            }
        }

        post("/api/host/ports/check") {
            val serverId = call.request.queryParameters["server_id"].orEmpty().trim()
            val networkRaw = call.request.queryParameters["network"].orEmpty().trim()
            val envRaw = call.request.queryParameters["env"].orEmpty().trim()
            if (serverId.isBlank() || networkRaw.isBlank() || envRaw.isBlank())
            {
                call.respond(
                    HttpStatusCode.BadRequest,
                    NodePortsResponse(ok = false, error = "missing_params", message = "server_id, network, and env are required"),
                )
                return@post
            }
            val network = NetworkId.parse(networkRaw)
            val env = EnvId.parse(envRaw)
            if (network == null || env == null)
            {
                call.respond(
                    HttpStatusCode.BadRequest,
                    NodePortsResponse(ok = false, error = "unknown_network_env"),
                )
                return@post
            }
            call.respondNodePorts(toolkit.checkHostPorts(serverId, network, env), live = true)
        }
    }
}
