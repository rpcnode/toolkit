package rpcnode.toolkit.panel.servers.presentation.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import rpcnode.toolkit.servers.application.ingest.IncomingHostDisk
import rpcnode.toolkit.servers.application.ingest.IncomingHostMetrics
import rpcnode.toolkit.servers.application.ingest.IngestServerMetricsResult
import rpcnode.toolkit.nodes.application.ingest.IngestNodeHeightsResult
import rpcnode.toolkit.nodes.application.ingest.MarkNodeStartedResult
import rpcnode.toolkit.nodes.application.ingest.NodeHeightSample
import rpcnode.toolkit.nodes.application.update.IngestClientUpdateProgressResult
import rpcnode.toolkit.nodes.application.update.IngestClientUpdateProgressUseCase
import rpcnode.toolkit.wiring.Toolkit

private val ingestLog = LoggerFactory.getLogger("rpcnode.http")

@Serializable
data class AgentIngestDiskBody(
    val name: String = "",
    val mount: String = "",
    @SerialName("free_gb") val freeGb: Double = 0.0,
    @SerialName("total_gb") val totalGb: Double = 0.0,
    @SerialName("used_pct") val usedPct: Double = 0.0,
    @SerialName("read_iops") val readIops: Double = 0.0,
    @SerialName("write_iops") val writeIops: Double = 0.0,
    @SerialName("read_mb_s") val readMbS: Double = 0.0,
    @SerialName("write_mb_s") val writeMbS: Double = 0.0,
    @SerialName("util_pct") val utilPct: Double = 0.0,
)

@Serializable
data class AgentIngestMetricsBody(
    @SerialName("server_id") val serverId: String = "",
    @SerialName("agent_url") val agentUrl: String = "",
    @SerialName("cpu_pct") val cpuPct: Double = 0.0,
    @SerialName("load_1") val load1: Double = 0.0,
    @SerialName("load_pct") val loadPct: Double = 0.0,
    val ncpu: Int = 0,
    @SerialName("mem_pct") val memPct: Double = 0.0,
    @SerialName("mem_used_mb") val memUsedMb: Double = 0.0,
    @SerialName("mem_total_mb") val memTotalMb: Double = 0.0,
    @SerialName("disk_used_pct") val diskUsedPct: Double = 0.0,
    @SerialName("disk_used_gb") val diskUsedGb: Double = 0.0,
    @SerialName("disk_total_gb") val diskTotalGb: Double = 0.0,
    val disks: List<AgentIngestDiskBody> = emptyList(),
    val os: String = "",
    val arch: String = "",
    @SerialName("collected_at") val collectedAt: String = "",
    val version: String = "",
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
data class AgentIngestOkResponse(
    val ok: Boolean = true,
    @SerialName("server_id") val serverId: String,
    val status: String,
)

@Serializable
data class AgentIngestErrorResponse(
    val ok: Boolean = false,
    val error: String,
)

@Serializable
data class AgentNodeStartedBody(
    @SerialName("server_id") val serverId: String = "",
    @SerialName("node_id") val nodeId: String = "",
    val pid: Long = 0,
    @SerialName("client_version") val clientVersion: String = "",
)

@Serializable
data class AgentNodeStartedResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    val status: String = "",
    val error: String? = null,
)

@Serializable
data class AgentNodeHeightItemBody(
    @SerialName("node_id") val nodeId: String = "",
    val height: Long = 0,
    @SerialName("client_version") val clientVersion: String = "",
    @SerialName("size_on_disk") val sizeOnDisk: Long = -1,
    @SerialName("sync_pct") val syncPct: Double? = null,
    val syncing: Boolean = false,
)

@Serializable
data class AgentNodeHeightsBody(
    @SerialName("server_id") val serverId: String = "",
    val items: List<AgentNodeHeightItemBody> = emptyList(),
)

@Serializable
data class AgentNodeHeightsResponse(
    val ok: Boolean = true,
    val updated: Int = 0,
    val error: String? = null,
)

@Serializable
data class AgentClientUpdateEventBody(
    val id: String = "",
    val label: String = "",
    val detail: String = "",
    val at: String = "",
)

@Serializable
data class AgentClientUpdateProgressBody(
    @SerialName("server_id") val serverId: String = "",
    @SerialName("node_id") val nodeId: String = "",
    val phase: String = "",
    val step: String = "",
    val detail: String = "",
    val pct: Int = 0,
    val local: String = "",
    val latest: String = "",
    @SerialName("previous_version") val previousVersion: String = "",
    @SerialName("update_available") val updateAvailable: Boolean = false,
    @SerialName("last_error") val lastError: String = "",
    @SerialName("log_tail") val logTail: String = "",
    val event: AgentClientUpdateEventBody? = null,
)

@Serializable
data class AgentClientUpdateProgressResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    val error: String? = null,
)

/** Public agent API — Bearer is the host agent token, not a panel session. */
fun Application.agentIngestRoutes(toolkit: Toolkit, logIngest: Boolean = false)
{
    routing {
        post("/api/agent/v1/metrics") {
            val token = call.agentToken()
            val body = call.receive<AgentIngestMetricsBody>()
            when (
                val result = toolkit.ingestServerMetrics(
                    token,
                    IncomingHostMetrics(
                        serverId = body.serverId,
                        agentUrl = body.agentUrl,
                        cpuPct = body.cpuPct,
                        load1 = body.load1,
                        loadPct = body.loadPct,
                        ncpu = body.ncpu,
                        memPct = body.memPct,
                        memUsedMb = body.memUsedMb,
                        memTotalMb = body.memTotalMb,
                        diskUsedPct = body.diskUsedPct,
                        diskUsedGb = body.diskUsedGb,
                        diskTotalGb = body.diskTotalGb,
                        disks = body.disks.map {
                            IncomingHostDisk(
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
                        os = body.os,
                        arch = body.arch,
                        collectedAt = body.collectedAt,
                        agentVersion = body.version,
                        netRxMbps = body.netRxMbps,
                        netTxMbps = body.netTxMbps,
                        diskReadIops = body.diskReadIops,
                        diskWriteIops = body.diskWriteIops,
                        diskReadMbS = body.diskReadMbS,
                        diskWriteMbS = body.diskWriteMbS,
                        diskUtilPct = body.diskUtilPct,
                        diskBusy = body.diskBusy,
                    ),
                )
            )
            {
                is IngestServerMetricsResult.Ok ->
                {
                    if (logIngest)
                    {
                        ingestLog.info(
                            "ingest {} cpu={} mem={} load={} disks={} status={}",
                            result.serverId.value,
                            body.cpuPct,
                            body.memPct,
                            body.load1,
                            body.disks.size,
                            result.status,
                        )
                    }
                    call.respond(AgentIngestOkResponse(serverId = result.serverId.value, status = result.status))
                }
                IngestServerMetricsResult.Unauthorized ->
                    call.respond(HttpStatusCode.Unauthorized, AgentIngestErrorResponse(error = "unauthorized"))
            }
        }

        post("/api/agent/v1/nodes/started") {
            val token = call.agentToken()
            val body = call.receive<AgentNodeStartedBody>()
            when (val result = toolkit.markNodeStarted(token, body.serverId, body.nodeId, body.clientVersion))
            {
                is MarkNodeStartedResult.Ok ->
                    call.respond(AgentNodeStartedResponse(nodeId = result.nodeId, status = result.status))
                MarkNodeStartedResult.Unauthorized ->
                    call.respond(HttpStatusCode.Unauthorized, AgentNodeStartedResponse(ok = false, error = "unauthorized"))
                MarkNodeStartedResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, AgentNodeStartedResponse(ok = false, error = "not_found"))
            }
        }

        post("/api/agent/v1/nodes/height") {
            val token = call.agentToken()
            val body = call.receive<AgentNodeHeightsBody>()
            when (
                val result = toolkit.ingestNodeHeights(
                    token,
                    body.serverId,
                    body.items.map {
                        NodeHeightSample(
                            nodeId = it.nodeId,
                            height = it.height,
                            clientVersion = it.clientVersion,
                            sizeOnDisk = it.sizeOnDisk,
                            syncPct = it.syncPct,
                            syncing = it.syncing,
                        )
                    },
                )
            )
            {
                is IngestNodeHeightsResult.Ok ->
                    call.respond(AgentNodeHeightsResponse(updated = result.updated))
                IngestNodeHeightsResult.Unauthorized ->
                    call.respond(HttpStatusCode.Unauthorized, AgentNodeHeightsResponse(ok = false, error = "unauthorized"))
            }
        }

        post("/api/agent/v1/nodes/client-update") {
            val token = call.agentToken()
            val body = call.receive<AgentClientUpdateProgressBody>()
            when (
                val result = toolkit.ingestClientUpdateProgress(
                    token,
                    body.serverId,
                    IngestClientUpdateProgressUseCase.Payload(
                        nodeId = body.nodeId,
                        phase = body.phase,
                        step = body.step,
                        detail = body.detail,
                        pct = body.pct,
                        local = body.local,
                        latest = body.latest,
                        previousVersion = body.previousVersion,
                        updateAvailable = body.updateAvailable,
                        lastError = body.lastError,
                        logTail = body.logTail,
                        event = body.event?.let {
                            IngestClientUpdateProgressUseCase.Event(
                                id = it.id,
                                label = it.label,
                                detail = it.detail,
                                at = it.at,
                            )
                        },
                    ),
                )
            )
            {
                is IngestClientUpdateProgressResult.Ok ->
                    call.respond(AgentClientUpdateProgressResponse(nodeId = result.nodeId))
                IngestClientUpdateProgressResult.Unauthorized ->
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        AgentClientUpdateProgressResponse(ok = false, error = "unauthorized"),
                    )
                IngestClientUpdateProgressResult.NotFound ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        AgentClientUpdateProgressResponse(ok = false, error = "not_found"),
                    )
            }
        }
    }
}

private fun ApplicationCall.agentToken(): String
{
    val header = request.header(HttpHeaders.Authorization).orEmpty()
    val bearer = header.removePrefix("Bearer ").trim().takeIf { header.startsWith("Bearer ") }
    val raw = request.header("X-Api-Token")?.trim().orEmpty()
    return bearer?.ifBlank { null } ?: raw
}
