package rpcnode.toolkit.panel.servers.presentation.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rpcnode.toolkit.panel.install.presentation.http.installOrigin
import rpcnode.toolkit.panel.presentation.http.ServerConfig
import rpcnode.toolkit.servers.application.list.ListedServer
import rpcnode.toolkit.servers.application.probe.InvalidAgentKey
import rpcnode.toolkit.servers.application.probe.ProbeHostAgentResult
import rpcnode.toolkit.servers.application.probe.ProbeHostAgentUseCase
import rpcnode.toolkit.servers.application.register.RegisterServerResult
import rpcnode.toolkit.servers.application.remove.RemoveServerResult
import rpcnode.toolkit.servers.application.update.UpdateHostAgentResult
import rpcnode.toolkit.servers.application.update.UpdateServerResult
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerMetrics
import rpcnode.toolkit.servers.domain.model.agentVersionOutdated
import rpcnode.toolkit.wiring.Toolkit

@Serializable
data class ProbeServerBody(
    @SerialName("agent_url") val agentUrl: String = "",
    @SerialName("agent_key") val agentKey: String = "",
)

@Serializable
data class ProbeServerResponse(
    val ok: Boolean = true,
    @SerialName("agent_url") val agentUrl: String = "",
    val os: String = "",
    val arch: String = "",
    @SerialName("os_pretty") val osPretty: String = "",
    @SerialName("agent_version") val agentVersion: String = "",
)

@Serializable
data class ProbeServerErrorResponse(
    val ok: Boolean = false,
    val error: String,
    val message: String? = null,
)

@Serializable
data class ServerDiskResponse(
    val name: String,
    val mount: String,
    @SerialName("free_gb") val freeGb: Double,
    @SerialName("total_gb") val totalGb: Double,
    @SerialName("used_pct") val usedPct: Double,
    @SerialName("read_iops") val readIops: Double = 0.0,
    @SerialName("write_iops") val writeIops: Double = 0.0,
    @SerialName("read_mb_s") val readMbS: Double = 0.0,
    @SerialName("write_mb_s") val writeMbS: Double = 0.0,
    @SerialName("util_pct") val utilPct: Double = 0.0,
)

@Serializable
data class ServerMetricsResponse(
    @SerialName("cpu_pct") val cpuPct: Double,
    @SerialName("load_pct") val loadPct: Double,
    val ncpu: Int,
    @SerialName("mem_pct") val memPct: Double,
    @SerialName("mem_used_mb") val memUsedMb: Double,
    @SerialName("mem_total_mb") val memTotalMb: Double,
    @SerialName("disk_used_pct") val diskUsedPct: Double,
    @SerialName("disk_used_gb") val diskUsedGb: Double,
    @SerialName("disk_total_gb") val diskTotalGb: Double,
    @SerialName("load_1") val load1: Double,
    val disks: List<ServerDiskResponse> = emptyList(),
    val os: String = "",
    val arch: String = "",
    @SerialName("collected_at") val collectedAt: String = "",
    @SerialName("last_seen_at") val lastSeenAt: String = "",
    @SerialName("net_rx_mbps") val netRxMbps: Double = 0.0,
    @SerialName("net_tx_mbps") val netTxMbps: Double = 0.0,
    @SerialName("disk_read_iops") val diskReadIops: Double = 0.0,
    @SerialName("disk_write_iops") val diskWriteIops: Double = 0.0,
    @SerialName("disk_read_mb_s") val diskReadMbS: Double = 0.0,
    @SerialName("disk_write_mb_s") val diskWriteMbS: Double = 0.0,
    @SerialName("disk_util_pct") val diskUtilPct: Double = 0.0,
    @SerialName("disk_busy") val diskBusy: String = "",
)

@Serializable
data class RegistryServerResponse(
    val id: String,
    val name: String = "",
    @SerialName("agent_url") val agentUrl: String = "",
    val os: String = "",
    val arch: String = "",
    @SerialName("os_pretty") val osPretty: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val metrics: ServerMetricsResponse? = null,
    @SerialName("metrics_status") val metricsStatus: String = "unknown",
    @SerialName("metrics_stale") val metricsStale: Boolean = true,
    @SerialName("nodes_count") val nodesCount: Int = 0,
    @SerialName("can_delete") val canDelete: Boolean = true,
    @SerialName("remove_status") val removeStatus: String = "",
    @SerialName("agent_version") val agentVersion: String = "",
    @SerialName("latest_agent_version") val latestAgentVersion: String = "",
    @SerialName("agent_update_available") val agentUpdateAvailable: Boolean = false,
)

@Serializable
data class RegistryListResponse(
    val ok: Boolean = true,
    val items: List<RegistryServerResponse> = emptyList(),
    val count: Int = 0,
    @SerialName("latest_agent_version") val latestAgentVersion: String = "",
)

@Serializable
data class RegistryUpsertBody(
    val name: String = "",
    @SerialName("agent_url") val agentUrl: String = "",
    @SerialName("agent_key") val agentKey: String = "",
    val os: String = "",
    val arch: String = "",
    @SerialName("os_pretty") val osPretty: String = "",
    @SerialName("panel_url") val panelUrl: String = "",
)

@Serializable
data class RegistryItemOkResponse(
    val ok: Boolean = true,
    val item: RegistryServerResponse,
)

@Serializable
data class RegistryRemoveResponse(
    val ok: Boolean = true,
    val queued: Boolean = true,
    @SerialName("remove_status") val removeStatus: String = "removing",
)

@Serializable
data class AgentUpdateBody(
    val force: Boolean = false,
)

@Serializable
data class AgentUpdateResponse(
    val ok: Boolean = true,
    val updated: Boolean = false,
    val version: String = "",
    @SerialName("remote_version") val remoteVersion: String = "",
    val message: String = "",
)

fun Application.serversApiRoutes(
    probeHostAgent: ProbeHostAgentUseCase,
    toolkit: Toolkit,
    cfg: ServerConfig,
    agentReleaseVersion: () -> String = { "" },
)
{
    routing {
        get("/api/servers") {
            val latest = agentReleaseVersion()
            val items = toolkit.listServers().map { it.toListedResponse(latest) }
            call.respond(RegistryListResponse(items = items, count = items.size, latestAgentVersion = latest))
        }

        post("/api/servers") {
            val body = call.receive<RegistryUpsertBody>()
            val panelUrl = toolkit.resolvePanelOrigin(body.panelUrl, installOrigin(call, cfg))
            when (val result = toolkit.registerServer(
                agentUrlRaw = body.agentUrl,
                agentKey = body.agentKey,
                name = body.name,
                os = body.os,
                arch = body.arch,
                osPretty = body.osPretty,
                panelUrlRaw = panelUrl,
            ))
            {
                is RegisterServerResult.Created ->
                    call.respond(RegistryItemOkResponse(item = result.server.toResponse(agentReleaseVersion())))
                RegisterServerResult.AgentUrlRequired ->
                    call.respond(HttpStatusCode.BadRequest, ProbeServerErrorResponse(error = "agent_url_required"))
                RegisterServerResult.AgentKeyRequired ->
                    call.respond(HttpStatusCode.BadRequest, ProbeServerErrorResponse(error = "agent_key_required"))
                RegisterServerResult.PanelUrlRequired ->
                    call.respond(HttpStatusCode.BadRequest, ProbeServerErrorResponse(error = "panel_url_required"))
                is RegisterServerResult.InvalidAgentKey ->
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ProbeServerErrorResponse(
                            error = InvalidAgentKey.ERROR,
                            message = "${InvalidAgentKey.MESSAGE} (agent ${result.agentUrl})",
                        ),
                    )
                is RegisterServerResult.EnrollFailed ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ProbeServerErrorResponse(
                            error = "enroll_failed",
                            message = buildString {
                                append("Agent at ${result.agentUrl} did not accept panel enrollment")
                                if (result.detail.isNotBlank())
                                {
                                    append(" — ${result.detail}")
                                }
                            },
                        ),
                    )
                is RegisterServerResult.PanelUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ProbeServerErrorResponse(
                            error = "panel_unreachable",
                            message = "Agent at ${result.agentUrl} cannot reach the panel at ${result.panelUrl}. " +
                                "Use an origin the host can open (LAN/public IP), not 127.0.0.1 from this browser.",
                        ),
                    )
            }
        }

        put("/api/servers/{id}") {
            val id = call.parameters["id"].orEmpty()
            val body = call.receive<RegistryUpsertBody>()
            val panelUrl = toolkit.resolvePanelOrigin(body.panelUrl, installOrigin(call, cfg))
            when (
                val result = toolkit.updateServer(
                    idRaw = id,
                    agentUrlRaw = body.agentUrl,
                    agentKeyRaw = body.agentKey,
                    name = body.name,
                    panelUrlRaw = panelUrl,
                )
            )
            {
                is UpdateServerResult.Updated ->
                    call.respond(RegistryItemOkResponse(item = result.server.toResponse(agentReleaseVersion())))
                UpdateServerResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ProbeServerErrorResponse(error = "not_found"))
                UpdateServerResult.Removing ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        ProbeServerErrorResponse(
                            error = "removing",
                            message = "Server is being removed — wait or cancel removal first",
                        ),
                    )
                UpdateServerResult.AgentUrlRequired ->
                    call.respond(HttpStatusCode.BadRequest, ProbeServerErrorResponse(error = "agent_url_required"))
                UpdateServerResult.AgentKeyRequired ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ProbeServerErrorResponse(
                            error = "agent_key_required",
                            message = "No agent key stored — paste the host agent token",
                        ),
                    )
                UpdateServerResult.PanelUrlRequired ->
                    call.respond(HttpStatusCode.BadRequest, ProbeServerErrorResponse(error = "panel_url_required"))
                is UpdateServerResult.InvalidAgentKey ->
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ProbeServerErrorResponse(
                            error = InvalidAgentKey.ERROR,
                            message = "${InvalidAgentKey.MESSAGE} (agent ${result.agentUrl})",
                        ),
                    )
                is UpdateServerResult.EnrollFailed ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ProbeServerErrorResponse(
                            error = "enroll_failed",
                            message = buildString {
                                append("Agent at ${result.agentUrl} did not accept panel enrollment")
                                if (result.detail.isNotBlank())
                                {
                                    append(" — ${result.detail}")
                                }
                            },
                        ),
                    )
                is UpdateServerResult.PanelUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ProbeServerErrorResponse(
                            error = "panel_unreachable",
                            message = "Agent at ${result.agentUrl} cannot reach the panel at ${result.panelUrl}. " +
                                "Use an origin the host can open (LAN/public IP), not 127.0.0.1 from this browser.",
                        ),
                    )
            }
        }

        delete("/api/servers/{id}") {
            when (val result = toolkit.removeServer(call.parameters["id"].orEmpty()))
            {
                is RemoveServerResult.Queued ->
                    call.respond(RegistryRemoveResponse())
                RemoveServerResult.AlreadyQueued ->
                    call.respond(RegistryRemoveResponse())
                RemoveServerResult.NotFound, RemoveServerResult.AlreadyDeleted ->
                    call.respond(HttpStatusCode.NotFound, ProbeServerErrorResponse(error = "not_found"))
                is RemoveServerResult.HasNodes ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        ProbeServerErrorResponse(
                            error = "has_nodes",
                            message = "Remove ${result.count} node(s) first, then delete the server.",
                        ),
                    )
            }
        }

        post("/api/v1/agent/update") {
            val force = runCatching { call.receive<AgentUpdateBody>().force }.getOrDefault(false)
            when (val result = toolkit.updateHostAgent(call.request.queryParameters["server"].orEmpty(), force))
            {
                is UpdateHostAgentResult.Ok ->
                    call.respond(
                        AgentUpdateResponse(
                            updated = result.updated,
                            version = result.version,
                            remoteVersion = result.remoteVersion,
                            message = result.message,
                        ),
                    )
                UpdateHostAgentResult.MissingServer, UpdateHostAgentResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, ProbeServerErrorResponse(error = "not_found"))
                is UpdateHostAgentResult.Unreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ProbeServerErrorResponse(
                            error = "unreachable",
                            message = "No rpcnode-agent at ${result.agentUrl}",
                        ),
                    )
                is UpdateHostAgentResult.Failed ->
                    call.respond(
                        HttpStatusCode.fromValue(if (result.status in 400 until 600) result.status else 502),
                        ProbeServerErrorResponse(error = result.error, message = result.message),
                    )
            }
        }

        post("/api/servers/probe") {
            val body = call.receive<ProbeServerBody>()
            when (val result = probeHostAgent(body.agentUrl, body.agentKey))
            {
                is ProbeHostAgentResult.Ok ->
                    call.respond(
                        ProbeServerResponse(
                            agentUrl = result.agentUrl,
                            os = result.identity.os,
                            arch = result.identity.arch,
                            osPretty = result.identity.osPretty.ifBlank {
                                listOf(result.identity.os, result.identity.arch).filter { it.isNotBlank() }.joinToString("/")
                            },
                            agentVersion = result.identity.version,
                        ),
                    )
                ProbeHostAgentResult.MissingUrl ->
                    call.respond(HttpStatusCode.BadRequest, ProbeServerErrorResponse(error = "agent_url required"))
                ProbeHostAgentResult.MissingToken ->
                    call.respond(HttpStatusCode.BadRequest, ProbeServerErrorResponse(error = "agent_key required"))
                is ProbeHostAgentResult.InvalidToken ->
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ProbeServerErrorResponse(
                            error = InvalidAgentKey.ERROR,
                            message = "${InvalidAgentKey.MESSAGE} (agent ${result.agentUrl})",
                        ),
                    )
                is ProbeHostAgentResult.Unreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        ProbeServerErrorResponse(
                            error = "unreachable",
                            message = buildString {
                                append("No rpcnode-agent at ${result.agentUrl}")
                                if (result.detail.isNotBlank())
                                {
                                    append(" — ${result.detail}")
                                }
                            },
                        ),
                    )
            }
        }
    }
}

private fun ListedServer.toListedResponse(latest: String) = RegistryServerResponse(
    id = server.id.value,
    name = server.name,
    agentUrl = server.agentUrl,
    os = server.os.ifBlank { metrics?.os.orEmpty() },
    arch = server.arch.ifBlank { metrics?.arch.orEmpty() },
    osPretty = server.osPretty.ifBlank {
        listOf(metrics?.os, metrics?.arch).mapNotNull { it?.ifBlank { null } }.joinToString("/")
    },
    createdAt = server.createdAt,
    updatedAt = server.updatedAt,
    metrics = metrics?.toResponse(),
    metricsStatus = metricsStatus,
    metricsStale = metricsStale,
    nodesCount = nodesCount,
    canDelete = canDelete,
    removeStatus = removeStatus,
    agentVersion = server.agentVersion,
    latestAgentVersion = latest,
    agentUpdateAvailable = agentVersionOutdated(server.agentVersion, latest),
)

private fun Server.toResponse(latest: String = "") = RegistryServerResponse(
    id = id.value,
    name = name,
    agentUrl = agentUrl,
    os = os,
    arch = arch,
    osPretty = osPretty,
    createdAt = createdAt,
    updatedAt = updatedAt,
    agentVersion = agentVersion,
    latestAgentVersion = latest,
    agentUpdateAvailable = agentVersionOutdated(agentVersion, latest),
)

private fun ServerMetrics.toResponse() = ServerMetricsResponse(
    cpuPct = cpuPct,
    loadPct = loadPct,
    ncpu = ncpu,
    memPct = memPct,
    memUsedMb = memUsedMb,
    memTotalMb = memTotalMb,
    diskUsedPct = diskUsedPct,
    diskUsedGb = diskUsedGb,
    diskTotalGb = diskTotalGb,
    load1 = load1,
    disks = disks.map {
        ServerDiskResponse(
            name = it.name,
            mount = it.mount,
            freeGb = it.freeGb,
            totalGb = it.totalGb,
            usedPct = it.usedPct,
            readIops = it.readIops,
            writeIops = it.writeIops,
            readMbS = it.readMbS,
            writeMbS = it.writeMbS,
            utilPct = it.utilPct,
        )
    },
    os = os,
    arch = arch,
    collectedAt = collectedAt,
    lastSeenAt = lastSeenAt,
    netRxMbps = netRxMbps,
    netTxMbps = netTxMbps,
    diskReadIops = diskReadIops,
    diskWriteIops = diskWriteIops,
    diskReadMbS = diskReadMbS,
    diskWriteMbS = diskWriteMbS,
    diskUtilPct = diskUtilPct,
    diskBusy = diskBusy,
)
