package rpcnode.toolkit.agent.presentation.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rpcnode.toolkit.agent.application.enroll.EnrollPanelResult
import rpcnode.toolkit.agent.application.disks.GetHostDisksUseCase
import rpcnode.toolkit.agent.application.sysctl.GetHostSysctlUseCase
import rpcnode.toolkit.agent.domain.model.HostSysctlSnapshot
import rpcnode.toolkit.agent.application.enroll.EnrollPanelUseCase
import rpcnode.toolkit.agent.application.enroll.UnenrollPanelUseCase
import rpcnode.toolkit.agent.application.files.WriteHostFileResult
import rpcnode.toolkit.agent.application.files.WriteHostFileUseCase
import rpcnode.toolkit.agent.application.client.ClientSyncCommand
import rpcnode.toolkit.agent.application.client.ClientSyncResult
import rpcnode.toolkit.agent.application.client.ClientUpdateAcceptResult
import rpcnode.toolkit.agent.application.client.ClientUpdateCommand
import rpcnode.toolkit.agent.application.client.ClientUpdateSnapshot
import rpcnode.toolkit.agent.application.client.ClientRollbackResult
import rpcnode.toolkit.agent.application.client.SyncClientFromPanelUseCase
import rpcnode.toolkit.agent.application.client.UpdateClientOnHostUseCase
import rpcnode.toolkit.agent.application.metrics.CollectHostMetricsUseCase
import rpcnode.toolkit.agent.infrastructure.node.readNodeClientVersion
import rpcnode.toolkit.agent.application.node.RemoveNodeHostResult
import rpcnode.toolkit.agent.application.node.RemoveNodeHostUseCase
import rpcnode.toolkit.agent.application.node.ControlNodeUnitResult
import rpcnode.toolkit.agent.application.node.ControlNodeUnitUseCase
import rpcnode.toolkit.agent.application.node.GetNodeClientVersionResult
import rpcnode.toolkit.agent.application.node.GetNodeClientVersionUseCase
import rpcnode.toolkit.agent.application.node.GetNodeProcessLogsResult
import rpcnode.toolkit.agent.application.node.GetNodeProcessLogsUseCase
import rpcnode.toolkit.agent.application.node.NodeHeightPlan
import rpcnode.toolkit.agent.application.node.NodeLaunchPlan
import rpcnode.toolkit.agent.application.node.NodeStartCommand
import rpcnode.toolkit.agent.application.node.NodeStartProcessResult
import rpcnode.toolkit.agent.application.node.StartNodeProcessUseCase
import rpcnode.toolkit.agent.application.ports.CheckPortsUseCase
import rpcnode.toolkit.agent.application.snapshot.GetSnapshotProgressUseCase
import rpcnode.toolkit.agent.application.snapshot.ProbeSnapshotSpeedUseCase
import rpcnode.toolkit.agent.application.snapshot.StartSnapshotDownloadResult
import rpcnode.toolkit.agent.application.snapshot.StartSnapshotDownloadUseCase
import rpcnode.toolkit.agent.application.snapshot.StopSnapshotDownloadResult
import rpcnode.toolkit.agent.application.status.GetAgentIdentityUseCase
import rpcnode.toolkit.agent.application.update.UpdateAgentResult
import rpcnode.toolkit.agent.application.update.UpdateAgentUseCase
import rpcnode.toolkit.agent.domain.model.HostDiskInventory
import rpcnode.toolkit.agent.domain.model.HostMetrics

@Serializable
data class AgentErrorResponse(
    val ok: Boolean = false,
    val error: String,
    val message: String? = null,
)

@Serializable
data class AgentHealthResponse(
    val ok: Boolean = true,
    val alive: Boolean = true,
    val role: String = "rpcnode-agent",
    val version: String,
    val port: Int,
)

@Serializable
data class AgentIdentityResponse(
    val ok: Boolean = true,
    val role: String = "rpcnode-agent",
    val version: String,
    val os: String,
    val arch: String,
    @SerialName("os_pretty") val osPretty: String,
    val port: Int,
)

@Serializable
data class AgentDiskResponse(
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
data class AgentMetricsCurrentResponse(
    @SerialName("cpu_pct") val cpuPct: Double,
    @SerialName("load_1") val load1: Double,
    @SerialName("load_pct") val loadPct: Double,
    val ncpu: Int,
    @SerialName("mem_pct") val memPct: Double,
    @SerialName("mem_used_mb") val memUsedMb: Double,
    @SerialName("mem_total_mb") val memTotalMb: Double,
    @SerialName("disk_used_pct") val diskUsedPct: Double,
    @SerialName("disk_used_gb") val diskUsedGb: Double,
    @SerialName("disk_total_gb") val diskTotalGb: Double,
    val disks: List<AgentDiskResponse>,
    val os: String,
    val arch: String,
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
data class AgentMetricsResponse(
    val ok: Boolean = true,
    val version: String,
    val current: AgentMetricsCurrentResponse,
)

@Serializable
data class AgentEnrollBody(
    @SerialName("panel_url") val panelUrl: String = "",
    @SerialName("server_id") val serverId: String = "",
    @SerialName("ingest_path") val ingestPath: String = "",
)

@Serializable
data class AgentEnrollResponse(
    val ok: Boolean = true,
    val version: String,
    @SerialName("panel_url") val panelUrl: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("ingest_path") val ingestPath: String,
)

@Serializable
data class AgentUnenrollResponse(
    val ok: Boolean = true,
    val version: String,
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
    @SerialName("local_version") val localVersion: String = "",
    @SerialName("remote_version") val remoteVersion: String = "",
    val message: String = "",
    val steps: List<String> = emptyList(),
)

@Serializable
data class AgentPortsCheckBody(
    val ports: List<Int> = emptyList(),
)

@Serializable
data class AgentPortCheckItemResponse(
    val port: Int,
    val free: Boolean,
    val holder: String? = null,
)

@Serializable
data class AgentPortsCheckResponse(
    val ok: Boolean = true,
    val items: List<AgentPortCheckItemResponse> = emptyList(),
)

@Serializable
data class AgentHostDiskItemResponse(
    val name: String,
    val path: String = "",
    val model: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("size_human") val sizeHuman: String = "",
    val tran: String = "",
    val rota: Boolean = false,
    val type: String = "",
    val mountpoint: String = "",
    val fstype: String = "",
    @SerialName("fsavail_bytes") val fsavailBytes: Long = 0,
    @SerialName("fsused_pct") val fsusedPct: Double = 0.0,
    val preferred: Boolean = false,
    @SerialName("planned_mount") val plannedMount: String = "",
)

@Serializable
data class AgentHostMountItemResponse(
    val target: String,
    val source: String = "",
    val fstype: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("avail_bytes") val availBytes: Long = 0,
    @SerialName("avail_human") val availHuman: String = "",
    @SerialName("used_pct") val usedPct: Double = 0.0,
    @SerialName("disk_name") val diskName: String = "",
    @SerialName("disk_path") val diskPath: String = "",
    val tran: String = "",
    val rota: Boolean = false,
    val preferred: Boolean = false,
)

@Serializable
data class AgentHostDisksResponse(
    val ok: Boolean = true,
    val disks: List<AgentHostDiskItemResponse> = emptyList(),
    val mounts: List<AgentHostMountItemResponse> = emptyList(),
    val unused: List<AgentHostDiskItemResponse> = emptyList(),
)

@Serializable
data class AgentHostSysctlResponse(
    val ok: Boolean = true,
    val current: Map<String, Long?> = emptyMap(),
    val recommended: Map<String, Long> = emptyMap(),
    @SerialName("install_option_keys") val installOptionKeys: Map<String, String> = emptyMap(),
)

@Serializable
data class AgentSnapshotStartBody(
    @SerialName("job_id") val jobId: String = "",
    val url: String = "",
    @SerialName("dest_dir") val destDir: String = "",
    @SerialName("stream_unpack") val streamUnpack: Boolean = false,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
)

@Serializable
data class AgentSnapshotStartResponse(
    val ok: Boolean = true,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class AgentSnapshotProgressResponse(
    val ok: Boolean = true,
    val pct: Double? = null,
    val phase: String = "",
    val detail: String = "",
    val ready: Boolean = false,
    val failed: Boolean = false,
    val error: String? = null,
    @SerialName("log_tail") val logTail: List<String> = emptyList(),
)

@Serializable
data class AgentSnapshotStopBody(
    @SerialName("job_id") val jobId: String = "",
    @SerialName("wipe_dest") val wipeDest: Boolean = true,
)

@Serializable
data class AgentWriteFileBody(
    val path: String = "",
    val content: String = "",
)

@Serializable
data class AgentWriteFileResponse(
    val ok: Boolean = true,
    val path: String? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class AgentClientSyncBody(
    val network: String = "",
    val env: String = "",
    @SerialName("node_dir") val nodeDir: String = "",
    @SerialName("config_assignments") val configAssignments: Map<String, String> = emptyMap(),
    @SerialName("config_format") val configFormat: String = "hoocon",
    @SerialName("config_file") val configFile: String? = null,
    @SerialName("config_ini_section") val configIniSection: String? = null,
    @SerialName("config_omit_ini_keys") val configOmitIniKeys: List<String> = emptyList(),
)

@Serializable
data class AgentClientSyncResponse(
    val ok: Boolean = true,
    @SerialName("node_dir") val nodeDir: String? = null,
    val files: List<String> = emptyList(),
    @SerialName("config_path") val configPath: String? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class AgentClientUpdateInfoResponse(
    val local: String = "",
    val latest: String = "",
    @SerialName("previous_version") val previousVersion: String = "",
    @SerialName("update_available") val updateAvailable: Boolean = false,
    val phase: String = "idle",
    val step: String = "",
    val detail: String = "",
    val pct: Int = 0,
    @SerialName("last_error") val lastError: String = "",
    @SerialName("log_tail") val logTail: String = "",
    val channel: String = "",
)

@Serializable
data class AgentClientUpdateStatusResponse(
    val ok: Boolean = true,
    @SerialName("client_update") val clientUpdate: AgentClientUpdateInfoResponse = AgentClientUpdateInfoResponse(),
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class AgentClientUpdateBody(
    @SerialName("node_id") val nodeId: String = "",
    val network: String = "",
    val env: String = "",
    @SerialName("node_dir") val nodeDir: String = "",
    @SerialName("config_assignments") val configAssignments: Map<String, String> = emptyMap(),
    @SerialName("config_format") val configFormat: String = "hoocon",
    @SerialName("config_file") val configFile: String? = null,
    @SerialName("config_ini_section") val configIniSection: String? = null,
    @SerialName("config_omit_ini_keys") val configOmitIniKeys: List<String> = emptyList(),
    @SerialName("http_port") val httpPort: Int = 0,
    val program: String = "",
    @SerialName("client_version") val clientVersion: String = "",
    val launch: AgentNodeLaunchBody = AgentNodeLaunchBody(),
    val height: AgentNodeHeightBody = AgentNodeHeightBody(),
)

@Serializable
data class AgentClientUpdateAcceptResponse(
    val ok: Boolean = true,
    val accepted: Boolean = false,
    @SerialName("client_update") val clientUpdate: AgentClientUpdateInfoResponse = AgentClientUpdateInfoResponse(),
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class AgentClientRollbackBody(
    @SerialName("node_id") val nodeId: String = "",
    val network: String = "",
    val env: String = "",
)

@Serializable
data class AgentNodeStartBody(
    @SerialName("node_id") val nodeId: String = "",
    val network: String = "",
    val env: String = "",
    @SerialName("node_dir") val nodeDir: String = "",
    @SerialName("config_file") val configFile: String? = null,
    @SerialName("http_port") val httpPort: Int = 0,
    val program: String = "",
    @SerialName("client_version") val clientVersion: String = "",
    val launch: AgentNodeLaunchBody = AgentNodeLaunchBody(),
    val height: AgentNodeHeightBody = AgentNodeHeightBody(),
)

@Serializable
data class AgentNodeLaunchBody(
    val kind: String = "",
    val entry: String = "",
    val args: List<String> = emptyList(),
    @SerialName("extract_archive_glob") val extractArchiveGlob: String? = null,
    @SerialName("normalize_dir") val normalizeDir: String? = null,
    @SerialName("java_major") val javaMajor: Int? = null,
    @SerialName("log_file") val logFile: String? = null,
)

@Serializable
data class AgentNodeHeightBody(
    val kind: String = "",
    @SerialName("port_role") val portRole: String = "",
)

@Serializable
data class AgentNodeStartResponse(
    val ok: Boolean = true,
    val pid: Long = 0,
    @SerialName("already_running") val alreadyRunning: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class AgentNodeLogsResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    val path: String = "",
    val lines: List<String> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class AgentNodeClientVersionResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("client_version") val clientVersion: String = "",
    val path: String = "",
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class AgentNodeRemoveBody(
    @SerialName("node_id") val nodeId: String = "",
    val network: String = "",
    val env: String = "",
    @SerialName("node_dir") val nodeDir: String? = null,
    @SerialName("wipe_data") val wipeData: Boolean = false,
)

@Serializable
data class AgentNodeRemoveResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("wipe_data") val wipeData: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class AgentNodeUnitControlBody(
    @SerialName("node_id") val nodeId: String = "",
    val network: String = "",
    val env: String = "",
)

@Serializable
data class AgentNodeUnitControlResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    val pid: Long = 0,
    val action: String = "",
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class AgentSnapshotStopResponse(
    val ok: Boolean = true,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class AgentSnapshotProbeSampleBody(
    val id: String = "",
    val url: String = "",
)

@Serializable
data class AgentSnapshotProbeBody(
    val samples: List<AgentSnapshotProbeSampleBody> = emptyList(),
)

@Serializable
data class AgentSnapshotProbeResultResponse(
    val id: String = "",
    val available: Boolean = false,
    @SerialName("bytes_per_sec") val bytesPerSec: Long? = null,
    @SerialName("sample_bytes") val sampleBytes: Long? = null,
    @SerialName("latency_ms") val latencyMs: Long? = null,
    val detail: String? = null,
)

@Serializable
data class AgentSnapshotProbeResponse(
    val ok: Boolean = true,
    val results: List<AgentSnapshotProbeResultResponse> = emptyList(),
    val error: String? = null,
)

fun Application.agentApiRoutes(
    cfg: AgentConfig,
    identity: GetAgentIdentityUseCase,
    collectMetrics: CollectHostMetricsUseCase,
    enrollPanel: EnrollPanelUseCase,
    unenrollPanel: UnenrollPanelUseCase,
    updateAgent: UpdateAgentUseCase,
    checkPorts: CheckPortsUseCase,
    getHostDisks: GetHostDisksUseCase,
    getHostSysctl: GetHostSysctlUseCase,
    startSnapshot: StartSnapshotDownloadUseCase,
    snapshotProgress: GetSnapshotProgressUseCase,
    probeSnapshotSpeed: ProbeSnapshotSpeedUseCase,
    writeHostFile: WriteHostFileUseCase = WriteHostFileUseCase(),
    syncClient: SyncClientFromPanelUseCase? = null,
    updateClient: UpdateClientOnHostUseCase? = null,
    startNode: StartNodeProcessUseCase? = null,
    getNodeLogs: GetNodeProcessLogsUseCase? = null,
    getNodeClientVersion: GetNodeClientVersionUseCase? = null,
    controlNodeUnit: ControlNodeUnitUseCase? = null,
    removeNodeHost: RemoveNodeHostUseCase? = null,
)
{
    routing {
        get("/healthz") {
            if (!call.authorized(cfg.token))
            {
                return@get
            }
            val got = identity()
            call.respond(
                AgentHealthResponse(
                    version = got.version,
                    port = got.port,
                ),
            )
        }
        get("/api/v1/agent") {
            if (!call.authorized(cfg.token))
            {
                return@get
            }
            val got = identity()
            call.respond(
                AgentIdentityResponse(
                    version = got.version,
                    os = got.os,
                    arch = got.arch,
                    osPretty = got.osPretty,
                    port = got.port,
                ),
            )
        }
        get("/api/v1/metrics") {
            if (!call.authorized(cfg.token))
            {
                return@get
            }
            call.respond(collectMetrics().toResponse(cfg.version))
        }
        get("/api/v1/host/disks") {
            if (!call.authorized(cfg.token))
            {
                return@get
            }
            call.respond(getHostDisks().toResponse())
        }
        get("/api/v1/host/sysctl") {
            if (!call.authorized(cfg.token))
            {
                return@get
            }
            call.respond(getHostSysctl().toResponse())
        }
        get("/api/metrics.json") {
            if (!call.authorized(cfg.token))
            {
                return@get
            }
            call.respond(collectMetrics().toResponse(cfg.version))
        }
        post("/api/v1/enroll") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val body = call.receive<AgentEnrollBody>()
            when (val result = enrollPanel(body.panelUrl, body.serverId, body.ingestPath))
            {
                is EnrollPanelResult.Ok ->
                    call.respond(
                        AgentEnrollResponse(
                            version = cfg.version,
                            panelUrl = result.enrollment.panelUrl,
                            serverId = result.enrollment.serverId,
                            ingestPath = result.enrollment.ingestPath,
                        ),
                    )
                EnrollPanelResult.MissingPanelUrl ->
                    call.respond(HttpStatusCode.BadRequest, AgentErrorResponse(error = "panel_url_required"))
                EnrollPanelResult.MissingServerId ->
                    call.respond(HttpStatusCode.BadRequest, AgentErrorResponse(error = "server_id_required"))
                EnrollPanelResult.PanelUnreachable ->
                    call.respond(HttpStatusCode.BadGateway, AgentErrorResponse(error = "panel_unreachable"))
                is EnrollPanelResult.StoreFailed ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AgentErrorResponse(error = "enroll_store_failed", message = result.detail),
                    )
            }
        }
        post("/api/v1/unenroll") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            unenrollPanel()
            call.respond(AgentUnenrollResponse(version = cfg.version))
        }
        post("/api/v1/agent/update") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val force = runCatching { call.receive<AgentUpdateBody>().force }.getOrDefault(false)
            when (val result = updateAgent(force))
            {
                is UpdateAgentResult.UpToDate ->
                    call.respond(
                        AgentUpdateResponse(
                            updated = false,
                            version = result.version,
                            localVersion = result.version,
                            remoteVersion = result.remoteVersion,
                            message = "already on ${result.version}",
                        ),
                    )
                is UpdateAgentResult.Updated ->
                    call.respond(
                        AgentUpdateResponse(
                            updated = true,
                            version = result.version,
                            localVersion = result.localVersion,
                            remoteVersion = result.version,
                            message = "installed ${result.localVersion} → ${result.version}; restarting",
                            steps = result.steps,
                        ),
                    )
                UpdateAgentResult.ChannelUnavailable ->
                    call.respond(HttpStatusCode.BadGateway, AgentErrorResponse(error = "channel_unavailable"))
                is UpdateAgentResult.Failed ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AgentErrorResponse(error = "update_failed"),
                    )
                UpdateAgentResult.InProgress ->
                    call.respond(HttpStatusCode.Conflict, AgentErrorResponse(error = "update_in_progress"))
            }
        }
        post("/api/v1/agent/ports/check") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val body = call.receive<AgentPortsCheckBody>()
            val checked = checkPorts(body.ports)
            call.respond(
                AgentPortsCheckResponse(
                    items = checked.map {
                        AgentPortCheckItemResponse(port = it.port, free = it.free, holder = it.holder)
                    },
                ),
            )
        }
        post("/api/v1/snapshot/start") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val body = call.receive<AgentSnapshotStartBody>()
            when (
                startSnapshot(
                    jobId = body.jobId,
                    url = body.url,
                    destDir = body.destDir,
                    streamUnpack = body.streamUnpack,
                    sizeBytes = body.sizeBytes,
                )
            )
            {
                StartSnapshotDownloadResult.Started ->
                    call.respond(AgentSnapshotStartResponse())
                StartSnapshotDownloadResult.AlreadyRunning ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        AgentSnapshotStartResponse(ok = false, error = "already_running"),
                    )
            }
        }
        get("/api/v1/snapshot/progress") {
            if (!call.authorized(cfg.token))
            {
                return@get
            }
            val jobId = call.request.queryParameters["job_id"].orEmpty()
            val job = snapshotProgress(jobId)
            if (job == null)
            {
                call.respond(
                    AgentSnapshotProgressResponse(
                        ok = false,
                        phase = "idle",
                        detail = "No snapshot job yet",
                    ),
                )
                return@get
            }
            call.respond(
                AgentSnapshotProgressResponse(
                    pct = job.pct,
                    phase = job.phase,
                    detail = job.detail,
                    ready = job.ready,
                    failed = job.failed,
                    error = job.error.ifBlank { null },
                    logTail = job.logTail,
                ),
            )
        }
        post("/api/v1/snapshot/stop") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val body = call.receive<AgentSnapshotStopBody>()
            when (startSnapshot.stop(body.jobId, wipeDest = body.wipeDest))
            {
                StopSnapshotDownloadResult.Stopped ->
                    call.respond(
                        AgentSnapshotStopResponse(
                            message = "Snapshot stopped — destination wiped",
                        ),
                    )
                StopSnapshotDownloadResult.NotRunning ->
                    call.respond(
                        AgentSnapshotStopResponse(
                            ok = true,
                            message = "No running snapshot job",
                        ),
                    )
            }
        }
        post("/api/v1/snapshot/probe") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val body = call.receive<AgentSnapshotProbeBody>()
            val results = probeSnapshotSpeed(
                body.samples.map {
                    rpcnode.toolkit.agent.application.snapshot.SnapshotSpeedSampleRequest(
                        id = it.id,
                        url = it.url,
                    )
                },
            )
            call.respond(
                AgentSnapshotProbeResponse(
                    results = results.map {
                        AgentSnapshotProbeResultResponse(
                            id = it.id,
                            available = it.available,
                            bytesPerSec = it.bytesPerSec,
                            sampleBytes = it.sampleBytes,
                            latencyMs = it.latencyMs,
                            detail = it.detail,
                        )
                    },
                ),
            )
        }
        post("/api/v1/files/write") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val body = call.receive<AgentWriteFileBody>()
            when (val result = writeHostFile(body.path, body.content))
            {
                is WriteHostFileResult.Ok ->
                    call.respond(AgentWriteFileResponse(path = result.path))
                WriteHostFileResult.InvalidPath ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AgentWriteFileResponse(ok = false, error = "invalid_path", message = "Absolute path required"),
                    )
                is WriteHostFileResult.Failed ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AgentWriteFileResponse(ok = false, error = "write_failed", message = result.detail),
                    )
            }
        }
        post("/api/v1/client/sync") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val sync = syncClient
            if (sync == null)
            {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    AgentClientSyncResponse(ok = false, error = "not_wired", message = "Client sync not configured"),
                )
                return@post
            }
            val body = call.receive<AgentClientSyncBody>()
            when (
                val result = sync(
                    ClientSyncCommand(
                        network = body.network,
                        env = body.env,
                        nodeDir = body.nodeDir,
                        configAssignments = body.configAssignments,
                        configFormat = body.configFormat,
                        configFile = body.configFile,
                        configIniSection = body.configIniSection,
                        configOmitIniKeys = body.configOmitIniKeys.toSet(),
                    ),
                )
            )
            {
                is ClientSyncResult.Ok ->
                    call.respond(
                        AgentClientSyncResponse(
                            nodeDir = result.nodeDir,
                            files = result.files,
                            configPath = result.configPath,
                        ),
                    )
                ClientSyncResult.MissingPanelUrl ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AgentClientSyncResponse(
                            ok = false,
                            error = "missing_panel_url",
                            message = "Agent is not enrolled (no panel URL) — set PANEL_URL or enroll",
                        ),
                    )
                ClientSyncResult.InvalidNodeDir ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AgentClientSyncResponse(ok = false, error = "invalid_node_dir"),
                    )
                is ClientSyncResult.PlanMissing ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        AgentClientSyncResponse(ok = false, error = "plan_missing", message = result.detail),
                    )
                is ClientSyncResult.DownloadFailed ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        AgentClientSyncResponse(ok = false, error = "download_failed", message = result.detail),
                    )
                is ClientSyncResult.PatchFailed ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AgentClientSyncResponse(ok = false, error = "patch_failed", message = result.detail),
                    )
            }
        }
        get("/api/v1/client") {
            if (!call.authorized(cfg.token))
            {
                return@get
            }
            val update = updateClient
            if (update == null)
            {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    AgentClientUpdateStatusResponse(ok = false, error = "not_wired", message = "Client update not configured"),
                )
                return@get
            }
            val nodeId = call.request.queryParameters["node_id"].orEmpty()
            val network = call.request.queryParameters["network"].orEmpty()
            val env = call.request.queryParameters["env"].orEmpty()
            val snap = update.status(nodeId = nodeId, network = network, env = env)
            call.respond(AgentClientUpdateStatusResponse(clientUpdate = snap.toResponse()))
        }
        post("/api/v1/client/check") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val update = updateClient
            if (update == null)
            {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    AgentClientUpdateStatusResponse(ok = false, error = "not_wired", message = "Client update not configured"),
                )
                return@post
            }
            val body = call.receive<AgentClientUpdateBody>()
            val nodeDir = body.nodeDir.trim()
            val local = if (nodeDir.isNotEmpty()) readNodeClientVersion(nodeDir) else ""
            val latest = body.clientVersion.trim()
            val snap = update.status(nodeId = body.nodeId, network = body.network, env = body.env)
            call.respond(
                AgentClientUpdateStatusResponse(
                    clientUpdate = snap.toResponse().copy(
                        local = local.ifEmpty { snap.local },
                        latest = latest.ifEmpty { snap.latest },
                        updateAvailable = latest.isNotEmpty() && local.isNotEmpty() &&
                            !local.equals(latest, ignoreCase = true),
                    ),
                ),
            )
        }
        post("/api/v1/client/update") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val update = updateClient
            if (update == null)
            {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    AgentClientUpdateAcceptResponse(ok = false, error = "not_wired", message = "Client update not configured"),
                )
                return@post
            }
            val body = call.receive<AgentClientUpdateBody>()
            when (
                val result = update.accept(
                    ClientUpdateCommand(
                        nodeId = body.nodeId,
                        network = body.network,
                        env = body.env,
                        nodeDir = body.nodeDir,
                        configAssignments = body.configAssignments,
                        configFormat = body.configFormat,
                        configFile = body.configFile,
                        configIniSection = body.configIniSection,
                        configOmitIniKeys = body.configOmitIniKeys.toSet(),
                        httpPort = body.httpPort,
                        program = body.program,
                        clientVersion = body.clientVersion,
                        launch = NodeLaunchPlan(
                            kind = body.launch.kind,
                            entry = body.launch.entry,
                            args = body.launch.args,
                            extractArchiveGlob = body.launch.extractArchiveGlob,
                            normalizeDir = body.launch.normalizeDir,
                            javaMajor = body.launch.javaMajor,
                            logFile = body.launch.logFile,
                        ),
                        height = NodeHeightPlan(
                            kind = body.height.kind,
                            portRole = body.height.portRole,
                        ),
                    ),
                )
            )
            {
                is ClientUpdateAcceptResult.Accepted ->
                    call.respond(
                        HttpStatusCode.Accepted,
                        AgentClientUpdateAcceptResponse(
                            accepted = true,
                            clientUpdate = result.snapshot.toResponse(),
                        ),
                    )
                ClientUpdateAcceptResult.Busy ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        AgentClientUpdateAcceptResponse(ok = false, error = "busy", message = "Client update already running"),
                    )
                ClientUpdateAcceptResult.NotRoot ->
                    call.respond(
                        HttpStatusCode.Forbidden,
                        AgentClientUpdateAcceptResponse(ok = false, error = "not_root", message = "Agent must run as root"),
                    )
                ClientUpdateAcceptResult.InvalidNodeDir ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AgentClientUpdateAcceptResponse(ok = false, error = "invalid_node_dir"),
                    )
                ClientUpdateAcceptResult.InvalidLaunch ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AgentClientUpdateAcceptResponse(ok = false, error = "invalid_launch"),
                    )
            }
        }
        post("/api/v1/client/update/rollback") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val update = updateClient
            if (update == null)
            {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    AgentClientUpdateAcceptResponse(ok = false, error = "not_wired", message = "Client update not configured"),
                )
                return@post
            }
            val body = call.receive<AgentClientRollbackBody>()
            when (val result = update.rollback(body.nodeId, body.network, body.env))
            {
                is ClientRollbackResult.Ok ->
                    call.respond(
                        AgentClientUpdateAcceptResponse(
                            accepted = true,
                            clientUpdate = result.snapshot.toResponse(),
                        ),
                    )
                ClientRollbackResult.NoPrevious ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AgentClientUpdateAcceptResponse(ok = false, error = "no_previous", message = "No previous client kept on host"),
                    )
                ClientRollbackResult.Busy ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        AgentClientUpdateAcceptResponse(ok = false, error = "busy", message = "Client update still running"),
                    )
                ClientRollbackResult.NotRoot ->
                    call.respond(
                        HttpStatusCode.Forbidden,
                        AgentClientUpdateAcceptResponse(ok = false, error = "not_root"),
                    )
                ClientRollbackResult.InvalidNodeDir ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AgentClientUpdateAcceptResponse(ok = false, error = "invalid_node_dir"),
                    )
                is ClientRollbackResult.Failed ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AgentClientUpdateAcceptResponse(ok = false, error = "rollback_failed", message = result.detail),
                    )
            }
        }
        post("/api/v1/node/start") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val start = startNode
            if (start == null)
            {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    AgentNodeStartResponse(ok = false, error = "not_wired", message = "Node start not configured"),
                )
                return@post
            }
            val body = call.receive<AgentNodeStartBody>()
            when (
                val result = start(
                    NodeStartCommand(
                        nodeId = body.nodeId,
                        network = body.network,
                        env = body.env,
                        nodeDir = body.nodeDir,
                        configFile = body.configFile,
                        httpPort = body.httpPort,
                        program = body.program,
                        clientVersion = body.clientVersion,
                        launch = NodeLaunchPlan(
                            kind = body.launch.kind,
                            entry = body.launch.entry,
                            args = body.launch.args,
                            extractArchiveGlob = body.launch.extractArchiveGlob,
                            normalizeDir = body.launch.normalizeDir,
                            javaMajor = body.launch.javaMajor,
                            logFile = body.launch.logFile,
                        ),
                        height = NodeHeightPlan(
                            kind = body.height.kind,
                            portRole = body.height.portRole,
                        ),
                    ),
                )
            )
            {
                is NodeStartProcessResult.Started ->
                    call.respond(
                        AgentNodeStartResponse(
                            pid = result.pid,
                            alreadyRunning = result.alreadyRunning,
                        ),
                    )
                NodeStartProcessResult.InvalidNodeDir ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AgentNodeStartResponse(ok = false, error = "invalid_node_dir"),
                    )
                NodeStartProcessResult.InvalidLaunch ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AgentNodeStartResponse(
                            ok = false,
                            error = "invalid_launch",
                            message = "Panel must send launch.kind and launch.entry from the chain Start recipe",
                        ),
                    )
                NodeStartProcessResult.UnsupportedNetwork ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AgentNodeStartResponse(
                            ok = false,
                            error = "unsupported_network",
                            message = "No chain runtime registered on the host agent for this network",
                        ),
                    )
                NodeStartProcessResult.NotRoot ->
                    call.respond(
                        HttpStatusCode.Forbidden,
                        AgentNodeStartResponse(
                            ok = false,
                            error = "not_root",
                            message = "Agent must run as root to install systemd units for node processes",
                        ),
                    )
                is NodeStartProcessResult.Failed ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AgentNodeStartResponse(ok = false, error = "start_failed", message = result.detail),
                    )
                is NodeStartProcessResult.Pending ->
                    call.respond(
                        HttpStatusCode.Accepted,
                        AgentNodeStartResponse(
                            ok = false,
                            error = "client_build_pending",
                            message = result.detail,
                        ),
                    )
            }
        }
        get("/api/v1/node/logs") {
            if (!call.authorized(cfg.token))
            {
                return@get
            }
            val logs = getNodeLogs
            if (logs == null)
            {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    AgentNodeLogsResponse(ok = false, error = "not_wired", message = "Node logs not configured"),
                )
                return@get
            }
            val nodeId = call.request.queryParameters["node_id"].orEmpty()
            val lines = call.request.queryParameters["lines"]?.toIntOrNull()
            val nodeDir = call.request.queryParameters["node_dir"]
            val logFile = call.request.queryParameters["log_file"]
            when (val result = logs(nodeId, lines, nodeDir, logFile))
            {
                is GetNodeProcessLogsResult.Ok ->
                    call.respond(
                        AgentNodeLogsResponse(
                            nodeId = result.view.nodeId,
                            path = result.view.path,
                            lines = result.view.lines,
                            truncated = result.view.truncated,
                        ),
                    )
                GetNodeProcessLogsResult.NotFound ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        AgentNodeLogsResponse(ok = false, error = "not_found", nodeId = nodeId),
                    )
                is GetNodeProcessLogsResult.NoLogYet ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        AgentNodeLogsResponse(
                            ok = false,
                            error = "no_log_yet",
                            nodeId = nodeId,
                            message = "Log not present yet: ${result.expectedPath}",
                        ),
                    )
            }
        }
        get("/api/v1/node/client-version") {
            if (!call.authorized(cfg.token))
            {
                return@get
            }
            val version = getNodeClientVersion
            if (version == null)
            {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    AgentNodeClientVersionResponse(
                        ok = false,
                        error = "not_wired",
                        message = "Node client version not configured",
                    ),
                )
                return@get
            }
            val nodeId = call.request.queryParameters["node_id"].orEmpty()
            val nodeDir = call.request.queryParameters["node_dir"]
            val seed = call.request.queryParameters["seed"]
            when (val result = version(nodeId, nodeDir, seed))
            {
                is GetNodeClientVersionResult.Ok ->
                    call.respond(
                        AgentNodeClientVersionResponse(
                            nodeId = result.view.nodeId,
                            clientVersion = result.view.clientVersion,
                            path = result.view.path,
                        ),
                    )
                GetNodeClientVersionResult.NotFound ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        AgentNodeClientVersionResponse(ok = false, error = "not_found", nodeId = nodeId),
                    )
            }
        }
        post("/api/v1/node/process/stop") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val control = controlNodeUnit
            if (control == null)
            {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    AgentNodeUnitControlResponse(
                        ok = false,
                        error = "not_wired",
                        message = "Node unit control not configured",
                    ),
                )
                return@post
            }
            val body = call.receive<AgentNodeUnitControlBody>()
            call.respondNodeUnitControl(
                control.stop(body.nodeId, body.network, body.env),
                body.nodeId,
                "stop",
            )
        }
        post("/api/v1/node/process/start") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val control = controlNodeUnit
            if (control == null)
            {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    AgentNodeUnitControlResponse(
                        ok = false,
                        error = "not_wired",
                        message = "Node unit control not configured",
                    ),
                )
                return@post
            }
            val body = call.receive<AgentNodeUnitControlBody>()
            call.respondNodeUnitControl(
                control.start(body.nodeId, body.network, body.env),
                body.nodeId,
                "start",
            )
        }
        post("/api/v1/node/remove") {
            if (!call.authorized(cfg.token))
            {
                return@post
            }
            val remove = removeNodeHost
            if (remove == null)
            {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    AgentNodeRemoveResponse(
                        ok = false,
                        error = "not_wired",
                        message = "Node remove not configured",
                    ),
                )
                return@post
            }
            val body = call.receive<AgentNodeRemoveBody>()
            when (
                val result = remove(
                    body.nodeId,
                    body.network,
                    body.env,
                    body.nodeDir,
                    body.wipeData,
                )
            )
            {
                RemoveNodeHostResult.Removed ->
                    call.respond(
                        AgentNodeRemoveResponse(
                            nodeId = body.nodeId,
                            wipeData = body.wipeData,
                            message = if (body.wipeData)
                            {
                                "Unit removed and node_dir wiped"
                            }
                            else
                            {
                                "Unit removed — chain data kept"
                            },
                        ),
                    )
                RemoveNodeHostResult.NotRoot ->
                    call.respond(
                        HttpStatusCode.Forbidden,
                        AgentNodeRemoveResponse(
                            ok = false,
                            nodeId = body.nodeId,
                            error = "not_root",
                            message = "Agent must run as root to remove systemd units",
                        ),
                    )
                RemoveNodeHostResult.NotFound ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        AgentNodeRemoveResponse(
                            ok = false,
                            nodeId = body.nodeId,
                            error = "node_id_required",
                            message = "node_id is required",
                        ),
                    )
                is RemoveNodeHostResult.Failed ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        AgentNodeRemoveResponse(
                            ok = false,
                            nodeId = body.nodeId,
                            error = "remove_failed",
                            message = result.detail,
                        ),
                    )
            }
        }
    }
}

private suspend fun ApplicationCall.respondNodeUnitControl(
    result: ControlNodeUnitResult,
    nodeId: String,
    action: String,
)
{
    when (result)
    {
        is ControlNodeUnitResult.Ok ->
            respond(
                AgentNodeUnitControlResponse(
                    nodeId = nodeId,
                    pid = result.pid,
                    action = result.action.ifBlank { action },
                ),
            )
        ControlNodeUnitResult.NotRoot ->
            respond(
                HttpStatusCode.Forbidden,
                AgentNodeUnitControlResponse(
                    ok = false,
                    nodeId = nodeId,
                    action = action,
                    error = "not_root",
                    message = "Agent must run as root to control systemd node units",
                ),
            )
        ControlNodeUnitResult.NotFound ->
            respond(
                HttpStatusCode.BadRequest,
                AgentNodeUnitControlResponse(
                    ok = false,
                    nodeId = nodeId,
                    action = action,
                    error = "node_id_required",
                ),
            )
        is ControlNodeUnitResult.Failed ->
            respond(
                HttpStatusCode.BadGateway,
                AgentNodeUnitControlResponse(
                    ok = false,
                    nodeId = nodeId,
                    action = action,
                    error = "control_failed",
                    message = result.detail,
                ),
            )
    }
}

private fun HostDiskInventory.toResponse() = AgentHostDisksResponse(
    disks = disks.map { it.toResponse() },
    mounts = mounts.map { it.toResponse() },
    unused = unused.map { it.toResponse() },
)

private fun HostSysctlSnapshot.toResponse() = AgentHostSysctlResponse(
    current = current,
    recommended = recommended,
    installOptionKeys = installOptionKeys,
)

private fun rpcnode.toolkit.agent.domain.model.BlockDevice.toResponse() = AgentHostDiskItemResponse(
    name = name,
    path = path,
    model = model,
    sizeBytes = sizeBytes,
    sizeHuman = sizeHuman,
    tran = tran,
    rota = rota,
    type = type,
    mountpoint = mountpoint,
    fstype = fstype,
    fsavailBytes = fsavailBytes,
    fsusedPct = fsusedPct,
    preferred = preferred,
    plannedMount = plannedMount,
)

private fun rpcnode.toolkit.agent.domain.model.MountPoint.toResponse() = AgentHostMountItemResponse(
    target = target,
    source = source,
    fstype = fstype,
    sizeBytes = sizeBytes,
    availBytes = availBytes,
    availHuman = availHuman,
    usedPct = usedPct,
    diskName = diskName,
    diskPath = diskPath,
    tran = tran,
    rota = rota,
    preferred = preferred,
)

private fun HostMetrics.toResponse(version: String) = AgentMetricsResponse(
    version = version,
    current = AgentMetricsCurrentResponse(
        cpuPct = cpuPct,
        load1 = load1,
        loadPct = loadPct,
        ncpu = ncpu,
        memPct = memPct,
        memUsedMb = memUsedMb,
        memTotalMb = memTotalMb,
        diskUsedPct = round2(diskUsedPct),
        diskUsedGb = round2(diskUsedGb),
        diskTotalGb = round2(diskTotalGb),
        disks = disks.map {
            AgentDiskResponse(
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
        netRxMbps = netRxMbps,
        netTxMbps = netTxMbps,
        diskReadIops = diskReadIops,
        diskWriteIops = diskWriteIops,
        diskReadMbS = diskReadMbS,
        diskWriteMbS = diskWriteMbS,
        diskUtilPct = diskUtilPct,
        diskBusy = diskBusy,
    ),
)

private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

private fun ClientUpdateSnapshot.toResponse() = AgentClientUpdateInfoResponse(
    local = local,
    latest = latest,
    previousVersion = previousVersion,
    updateAvailable = updateAvailable,
    phase = phase,
    step = step,
    detail = detail,
    pct = pct,
    lastError = lastError,
    logTail = logTail,
)

private suspend fun ApplicationCall.authorized(expected: String): Boolean
{
    if (expected.isBlank())
    {
        respond(HttpStatusCode.Unauthorized, AgentErrorResponse(error = "token_required"))
        return false
    }
    val header = request.header(HttpHeaders.Authorization).orEmpty()
    val bearer = header.removePrefix("Bearer ").trim().takeIf { header.startsWith("Bearer ") }
    val raw = request.header("X-Api-Token")?.trim().orEmpty()
    val got = bearer?.ifBlank { null } ?: raw
    if (got.isEmpty() || got != expected)
    {
        respond(HttpStatusCode.Unauthorized, AgentErrorResponse(error = "unauthorized"))
        return false
    }
    return true
}
