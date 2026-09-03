package rpcnode.toolkit.panel.nodes.presentation.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import rpcnode.toolkit.nodes.application.add.AddNodeResult
import rpcnode.toolkit.nodes.application.ports.NodePortsResult
import rpcnode.toolkit.nodes.application.disks.GetNodeDiskLayoutUseCase
import rpcnode.toolkit.nodes.application.disks.NodeDiskLayoutResult
import rpcnode.toolkit.nodes.application.disks.SaveNodeDiskLayoutUseCase
import rpcnode.toolkit.nodes.application.disks.SaveNodeDiskLayoutResult
import rpcnode.toolkit.nodes.application.options.SaveNodeInstallOptionsResult
import rpcnode.toolkit.nodes.application.options.SaveNodeInstallOptionsUseCase
import rpcnode.toolkit.nodes.application.config.ApplyNodeClientConfigResult
import rpcnode.toolkit.nodes.application.height.GetNodeHeightResult
import rpcnode.toolkit.nodes.application.logs.GetNodeLogsResult
import rpcnode.toolkit.nodes.application.version.GetNodeClientVersionResult
import rpcnode.toolkit.nodes.application.process.ControlNodeProcessResult
import rpcnode.toolkit.nodes.application.start.StartNodeResult
import rpcnode.toolkit.nodes.application.update.ClientUpdateInfo
import rpcnode.toolkit.nodes.application.update.GetNodeClientUpdateResult
import rpcnode.toolkit.nodes.application.update.RollbackNodeClientResult
import rpcnode.toolkit.nodes.application.update.UpdateNodeClientResult
import rpcnode.toolkit.servers.application.probe.InvalidAgentKey
import rpcnode.toolkit.nodes.application.remove.RemoveNodeMode
import rpcnode.toolkit.nodes.application.remove.RemoveNodeResult
import rpcnode.toolkit.networks.domain.model.ClientConfigFacts
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.application.snapshot.nodeNeedsSnapshot
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.infrastructure.http.DiskRoleDefResponse
import rpcnode.toolkit.nodes.infrastructure.http.NodeDiskLayoutResponse
import rpcnode.toolkit.nodes.infrastructure.http.decodeDiskLayoutBody
import rpcnode.toolkit.nodes.infrastructure.http.parseDiskLayoutJson
import rpcnode.toolkit.nodes.infrastructure.http.toResponse
import rpcnode.toolkit.wiring.Toolkit

@Serializable
data class NodeUpsertBody(
    @SerialName("server_id") val serverId: String = "",
    val network: String = "",
    val env: String = "",
    val name: String? = null,
)

@Serializable
data class NodeItemResponse(
    val id: String,
    @SerialName("server_id") val serverId: String,
    val name: String = "",
    val network: String,
    val env: String,
    @SerialName("public_port") val publicPort: Int = 0,
    @SerialName("agent_port") val agentPort: Int = 0,
    @SerialName("node_http_port") val nodeHttpPort: Int = 0,
    @SerialName("p2p_port") val p2pPort: Int = 0,
    val status: String,
    val height: Long = 0,
    @SerialName("height_at") val heightAt: String = "",
    @SerialName("network_height") val networkHeight: Long = 0,
    /** Host IBD/snap progress 0..100; -1 = unknown. */
    @SerialName("sync_pct") val syncPct: Double = -1.0,
    @SerialName("size_on_disk") val sizeOnDisk: Long = -1,
    @SerialName("client_version") val clientVersion: String = "",
    @SerialName("client_latest") val clientLatest: String = "",
    @SerialName("client_update_available") val clientUpdateAvailable: Boolean = false,
    @SerialName("needs_snapshot") val needsSnapshot: Boolean = false,
    @SerialName("disk_layout") val diskLayout: JsonElement? = null,
    @SerialName("install_options") val installOptions: JsonElement? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class NodesListResponse(
    val ok: Boolean = true,
    val items: List<NodeItemResponse> = emptyList(),
    val count: Int = 0,
    val source: String = "db",
)

@Serializable
data class NodeItemOkResponse(
    val ok: Boolean = true,
    val item: NodeItemResponse,
    val message: String? = null,
)

@Serializable
data class NodeDiskLayoutBody(
    @SerialName("disk_layout") val diskLayout: JsonElement,
)

@Serializable
data class ClientConfigBindingResponse(
    val path: String = "",
    val source: String = "",
    val description: String? = null,
    val role: String? = null,
    val option: String? = null,
    val value: String? = null,
    val relative: String? = null,
    val optional: Boolean = false,
    val default: String? = null,
    /** For source snapshot_kind: values by type id / kind (full, lite, archive). */
    val map: Map<String, String> = emptyMap(),
    @SerialName("when_install_option") val whenInstallOption: String? = null,
    @SerialName("when_install_option_value") val whenInstallOptionValue: String? = null,
    @SerialName("test_connect") val testConnect: ClientConfigTestConnectResponse? = null,
)

@Serializable
data class ClientConfigTestConnectResponse(
    val kind: String = "",
    val label: String = "Test connect",
    val help: String? = null,
)

@Serializable
data class ClientConfigResponse(
    val program: String = "",
    val format: String = "",
    val template: String? = null,
    val templates: Map<String, String> = emptyMap(),
    @SerialName("env_sections") val envSections: Map<String, String> = emptyMap(),
    val bindings: List<ClientConfigBindingResponse> = emptyList(),
)

@Serializable
data class NodeDiskLayoutGetResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    val network: String = "",
    val env: String = "",
    @SerialName("disk_layout") val diskLayout: NodeDiskLayoutResponse? = null,
    @SerialName("install_options") val installOptions: JsonElement? = null,
    @SerialName("multi_disk_roles") val multiDiskRoles: List<DiskRoleDefResponse> = emptyList(),
    @SerialName("layout_rules") val layoutRules: List<String> = emptyList(),
    val recommended: NodeDiskLayoutResponse? = null,
    @SerialName("client_config") val clientConfig: ClientConfigResponse? = null,
    val error: String? = null,
)

@Serializable
data class NodeDiskLayoutSaveResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    val item: NodeItemResponse? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class NodeInstallOptionsBody(
    @SerialName("snapshot") val snapshot: String? = null,
    @SerialName("install_options") val installOptions: JsonElement? = null,
)

@Serializable
data class NodeInstallOptionsSaveResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("install_options") val installOptions: JsonElement? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class NodeClientConfigApplyResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    val path: String? = null,
    @SerialName("install_options") val installOptions: JsonElement? = null,
    val files: List<String> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class NodeStartResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    val path: String? = null,
    val pid: Long = 0,
    val status: String = "",
    @SerialName("already_running") val alreadyRunning: Boolean = false,
    @SerialName("install_options") val installOptions: JsonElement? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class NodeClientUpdateInfoResponse(
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
    val events: List<NodeClientUpdateEventResponse> = emptyList(),
)

@Serializable
data class NodeClientUpdateEventResponse(
    val id: String = "",
    val label: String = "",
    val detail: String = "",
    val at: String = "",
)

@Serializable
data class NodeClientUpdateResponse(
    val ok: Boolean = true,
    val accepted: Boolean = false,
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("client_update") val clientUpdate: NodeClientUpdateInfoResponse = NodeClientUpdateInfoResponse(),
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class NodeHeightResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    val status: String = "",
    val height: Long = 0,
    @SerialName("height_at") val heightAt: String = "",
    @SerialName("network_height") val networkHeight: Long? = null,
    val behind: Long? = null,
    /** Host IBD/snap progress 0..100; null when unknown. */
    @SerialName("sync_pct") val syncPct: Double? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class NodeLogsResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    val path: String = "",
    val lines: List<String> = emptyList(),
    val truncated: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class NodeClientVersionResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("client_version") val clientVersion: String = "",
    val path: String = "",
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class NodeProcessControlResponse(
    val ok: Boolean = true,
    @SerialName("node_id") val nodeId: String = "",
    val pid: Long = 0,
    val action: String = "",
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class NodeErrorResponse(
    val ok: Boolean = false,
    val error: String,
    val message: String? = null,
    @SerialName("occupied_node_id") val occupiedNodeId: String? = null,
    @SerialName("occupied_status") val occupiedStatus: String? = null,
    @SerialName("occupied_env") val occupiedEnv: String? = null,
)

@Serializable
data class NodeRemoveBody(
    val id: String = "",
    val mode: String = "wipe",
    @SerialName("delete_files") val deleteFiles: Boolean = false,
    val force: Boolean = false,
)

@Serializable
data class NodeRemoveResponse(
    val ok: Boolean = true,
    val status: String = "removed",
    val id: String = "",
    @SerialName("panel_only") val panelOnly: Boolean = true,
    val message: String? = null,
)

fun Application.nodesApiRoutes(toolkit: Toolkit)
{
    routing {
        get("/api/nodes/{id}/disk-layout") {
            val id = call.parameters["id"].orEmpty()
            when (val result = toolkit.getNodeDiskLayout(id))
            {
                is NodeDiskLayoutResult.Ok ->
                    call.respond(
                        NodeDiskLayoutGetResponse(
                            nodeId = result.nodeId,
                            network = result.network,
                            env = result.env,
                            diskLayout = result.diskLayout?.toResponse(),
                            installOptions = parseDiskLayoutJson(result.installOptionsJson),
                            multiDiskRoles = result.multiDiskRoles.map { it.toResponse() },
                            layoutRules = result.layoutRules,
                            recommended = result.recommended?.toResponse(),
                            clientConfig = result.clientConfig?.toResponse(),
                        ),
                    )
                NodeDiskLayoutResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeDiskLayoutGetResponse(ok = false, error = "not_found"))
            }
        }

        put("/api/nodes/{id}/disk-layout") {
            val id = call.parameters["id"].orEmpty()
            val body = call.receive<NodeDiskLayoutBody>()
            when (val result = toolkit.saveNodeDiskLayout(id, decodeDiskLayoutBody(body.diskLayout)))
            {
                is SaveNodeDiskLayoutResult.Saved ->
                {
                    val node = toolkit.getNode(id)
                    call.respond(
                        NodeDiskLayoutSaveResponse(
                            nodeId = result.nodeId,
                            item = node?.toResponse(toolkit.networkFacts),
                        ),
                    )
                }
                SaveNodeDiskLayoutResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeDiskLayoutSaveResponse(ok = false, error = "not_found"))
            }
        }

        put("/api/nodes/{id}/install-options") {
            val id = call.parameters["id"].orEmpty()
            val body = call.receive<NodeInstallOptionsBody>()
            val optionsJson = body.installOptions?.let { decodeDiskLayoutBody(it) }
            when (val result = toolkit.saveNodeInstallOptions(id, body.snapshot, optionsJson))
            {
                is SaveNodeInstallOptionsResult.Saved ->
                    call.respond(
                        NodeInstallOptionsSaveResponse(
                            nodeId = result.nodeId,
                            installOptions = parseDiskLayoutJson(result.installOptionsJson),
                        ),
                    )
                SaveNodeInstallOptionsResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeInstallOptionsSaveResponse(ok = false, error = "not_found"))
                SaveNodeInstallOptionsResult.InvalidType ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeInstallOptionsSaveResponse(
                            ok = false,
                            error = "invalid_type",
                            message = "Unknown snapshot type for this network/env",
                        ),
                    )
            }
        }

        post("/api/nodes/{id}/client-config/apply") {
            val id = call.parameters["id"].orEmpty()
            val body = call.receive<NodeInstallOptionsBody>()
            val optionsJson = body.installOptions?.let { decodeDiskLayoutBody(it) }
            // Never pass body.snapshot — Start only syncs client files / patches config.
            when (val result = toolkit.applyNodeClientConfig(id, optionsJson, snapshotType = null))
            {
                is ApplyNodeClientConfigResult.Applied ->
                    call.respond(
                        NodeClientConfigApplyResponse(
                            nodeId = result.nodeId,
                            path = result.path,
                            installOptions = parseDiskLayoutJson(result.installOptionsJson),
                            files = result.files,
                        ),
                    )
                ApplyNodeClientConfigResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeClientConfigApplyResponse(ok = false, error = "not_found"))
                ApplyNodeClientConfigResult.ServerNotFound ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeClientConfigApplyResponse(ok = false, error = "server_not_found"),
                    )
                ApplyNodeClientConfigResult.NoClientConfig ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeClientConfigApplyResponse(
                            ok = false,
                            error = "no_client_config",
                            message = "No clientConfig bindings for this network",
                        ),
                    )
                ApplyNodeClientConfigResult.NoDiskLayout ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeClientConfigApplyResponse(
                            ok = false,
                            error = "no_disk_layout",
                            message = "Save a disk layout before applying client config",
                        ),
                    )
                ApplyNodeClientConfigResult.TemplateMissing ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeClientConfigApplyResponse(
                            ok = false,
                            error = "template_missing",
                            message = "Client config template missing under install/clients — sync the client first",
                        ),
                    )
                ApplyNodeClientConfigResult.InvalidType ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeClientConfigApplyResponse(
                            ok = false,
                            error = "invalid_options",
                            message = "Could not save install options",
                        ),
                    )
                is ApplyNodeClientConfigResult.AgentUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeClientConfigApplyResponse(
                            ok = false,
                            error = "agent_unreachable",
                            message = result.detail.ifBlank { "Host agent did not answer" },
                        ),
                    )
                is ApplyNodeClientConfigResult.SyncFailed ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeClientConfigApplyResponse(
                            ok = false,
                            error = result.error,
                            message = result.message,
                        ),
                    )
            }
        }

        get("/api/nodes/{id}/client/update") {
            val id = call.parameters["id"].orEmpty()
            when (val result = toolkit.getNodeClientUpdate(id))
            {
                is GetNodeClientUpdateResult.Ok ->
                    call.respond(
                        NodeClientUpdateResponse(
                            nodeId = result.nodeId,
                            clientUpdate = result.info.toResponse(),
                        ),
                    )
                GetNodeClientUpdateResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeClientUpdateResponse(ok = false, error = "not_found"))
                GetNodeClientUpdateResult.ServerNotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeClientUpdateResponse(ok = false, error = "server_not_found"))
                is GetNodeClientUpdateResult.AgentUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeClientUpdateResponse(
                            ok = false,
                            error = "agent_unreachable",
                            message = result.detail.ifBlank { "Host agent did not answer" },
                        ),
                    )
                is GetNodeClientUpdateResult.Failed ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeClientUpdateResponse(ok = false, error = result.error, message = result.message),
                    )
            }
        }

        post("/api/nodes/{id}/client/update") {
            val id = call.parameters["id"].orEmpty()
            when (val result = toolkit.updateNodeClient(id))
            {
                is UpdateNodeClientResult.Accepted ->
                    call.respond(
                        HttpStatusCode.Accepted,
                        NodeClientUpdateResponse(
                            accepted = true,
                            nodeId = result.nodeId,
                            clientUpdate = result.info.toResponse(),
                        ),
                    )
                UpdateNodeClientResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeClientUpdateResponse(ok = false, error = "not_found"))
                UpdateNodeClientResult.ServerNotFound ->
                    call.respond(HttpStatusCode.BadRequest, NodeClientUpdateResponse(ok = false, error = "server_not_found"))
                UpdateNodeClientResult.NoClientConfig ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeClientUpdateResponse(ok = false, error = "no_client_config", message = "No clientConfig for this network"),
                    )
                UpdateNodeClientResult.NoDiskLayout ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeClientUpdateResponse(ok = false, error = "no_disk_layout", message = "Save a disk layout first"),
                    )
                UpdateNodeClientResult.TemplateMissing ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeClientUpdateResponse(ok = false, error = "template_missing", message = "Client files missing — sync the client first"),
                    )
                UpdateNodeClientResult.UnsupportedNetwork ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeClientUpdateResponse(ok = false, error = "unsupported_network"),
                    )
                is UpdateNodeClientResult.AgentUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeClientUpdateResponse(
                            ok = false,
                            error = "agent_unreachable",
                            message = result.detail.ifBlank { "Host agent did not answer" },
                        ),
                    )
                is UpdateNodeClientResult.Failed ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeClientUpdateResponse(ok = false, error = result.error, message = result.message),
                    )
            }
        }

        post("/api/nodes/{id}/client/update/rollback") {
            val id = call.parameters["id"].orEmpty()
            when (val result = toolkit.rollbackNodeClient(id))
            {
                is RollbackNodeClientResult.Ok ->
                    call.respond(
                        NodeClientUpdateResponse(
                            accepted = true,
                            nodeId = result.nodeId,
                            clientUpdate = result.info.toResponse(),
                        ),
                    )
                RollbackNodeClientResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeClientUpdateResponse(ok = false, error = "not_found"))
                RollbackNodeClientResult.ServerNotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeClientUpdateResponse(ok = false, error = "server_not_found"))
                is RollbackNodeClientResult.AgentUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeClientUpdateResponse(
                            ok = false,
                            error = "agent_unreachable",
                            message = result.detail.ifBlank { "Host agent did not answer" },
                        ),
                    )
                is RollbackNodeClientResult.Failed ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeClientUpdateResponse(ok = false, error = result.error, message = result.message),
                    )
            }
        }

        post("/api/nodes/{id}/start") {
            val id = call.parameters["id"].orEmpty()
            val body = call.receive<NodeInstallOptionsBody>()
            val optionsJson = body.installOptions?.let { decodeDiskLayoutBody(it) }
            when (val result = toolkit.startNode(id, optionsJson))
            {
                is StartNodeResult.Started ->
                    call.respond(
                        NodeStartResponse(
                            nodeId = result.nodeId,
                            path = result.path,
                            pid = result.pid,
                            status = result.status,
                            alreadyRunning = result.alreadyRunning,
                            installOptions = parseDiskLayoutJson(result.installOptionsJson),
                        ),
                    )
                StartNodeResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeStartResponse(ok = false, error = "not_found"))
                StartNodeResult.ServerNotFound ->
                    call.respond(HttpStatusCode.BadRequest, NodeStartResponse(ok = false, error = "server_not_found"))
                StartNodeResult.NoClientConfig ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeStartResponse(
                            ok = false,
                            error = "no_client_config",
                            message = "No clientConfig bindings for this network",
                        ),
                    )
                StartNodeResult.NoDiskLayout ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeStartResponse(
                            ok = false,
                            error = "no_disk_layout",
                            message = "Save a disk layout before starting",
                        ),
                    )
                StartNodeResult.TemplateMissing ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeStartResponse(
                            ok = false,
                            error = "template_missing",
                            message = "Client config template missing under install/clients — sync the client first",
                        ),
                    )
                StartNodeResult.InvalidType ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeStartResponse(ok = false, error = "invalid_options", message = "Could not save install options"),
                    )
                StartNodeResult.UnsupportedNetwork ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeStartResponse(
                            ok = false,
                            error = "unsupported_network",
                            message = "Start is not implemented for this chain on the host agent yet",
                        ),
                    )
                is StartNodeResult.AgentUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeStartResponse(
                            ok = false,
                            error = "agent_unreachable",
                            message = result.detail.ifBlank { "Host agent did not answer" },
                        ),
                    )
                is StartNodeResult.SyncFailed ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeStartResponse(ok = false, error = result.error, message = result.message),
                    )
                is StartNodeResult.StartFailed ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeStartResponse(ok = false, error = result.error, message = result.message),
                    )
                is StartNodeResult.BuildPending ->
                    call.respond(
                        HttpStatusCode.Accepted,
                        NodeStartResponse(
                            ok = false,
                            error = result.error.ifBlank { "client_build_pending" },
                            message = result.message,
                        ),
                    )
            }
        }

        get("/api/nodes") {
            val items = toolkit.listNodes().map { it.toResponse(toolkit.networkFacts) }
            call.respond(NodesListResponse(items = items, count = items.size))
        }

        get("/api/nodes/{id}") {
            val id = call.parameters["id"].orEmpty()
            val node = toolkit.getNode(id)
            if (node == null)
            {
                call.respond(HttpStatusCode.NotFound, NodeErrorResponse(error = "not_found"))
                return@get
            }
            call.respond(NodeItemOkResponse(item = node.toResponse(toolkit.networkFacts)))
        }

        get("/api/nodes/{id}/height") {
            val id = call.parameters["id"].orEmpty()
            when (val result = toolkit.getNodeHeight(id))
            {
                is GetNodeHeightResult.Ok ->
                    call.respond(
                        NodeHeightResponse(
                            nodeId = result.view.nodeId,
                            status = result.view.status,
                            height = result.view.height,
                            heightAt = result.view.heightAt,
                            networkHeight = result.view.networkHeight,
                            behind = result.view.behind,
                            syncPct = result.view.syncPct,
                        ),
                    )
                GetNodeHeightResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeHeightResponse(ok = false, error = "not_found"))
            }
        }

        get("/api/nodes/{id}/logs") {
            val id = call.parameters["id"].orEmpty()
            val lines = call.request.queryParameters["lines"]?.toIntOrNull()
            val logFile = call.request.queryParameters["log_file"]
            when (val result = toolkit.getNodeLogs(id, lines, logFile))
            {
                is GetNodeLogsResult.Ok ->
                    call.respond(
                        NodeLogsResponse(
                            nodeId = result.view.nodeId,
                            path = result.view.path,
                            lines = result.view.lines,
                            truncated = result.view.truncated,
                        ),
                    )
                GetNodeLogsResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeLogsResponse(ok = false, error = "not_found"))
                GetNodeLogsResult.ServerNotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeLogsResponse(ok = false, error = "server_not_found"))
                GetNodeLogsResult.AgentUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeLogsResponse(ok = false, error = "agent_unreachable", message = "Host agent did not answer"),
                    )
                GetNodeLogsResult.InvalidAgentKey ->
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        NodeLogsResponse(
                            ok = false,
                            error = InvalidAgentKey.ERROR,
                            message = InvalidAgentKey.MESSAGE,
                        ),
                    )
                GetNodeLogsResult.Unavailable ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        NodeLogsResponse(
                            ok = false,
                            error = "unavailable",
                            message = "Node process log is not available on the host yet",
                        ),
                    )
            }
        }

        get("/api/nodes/{id}/client-version") {
            val id = call.parameters["id"].orEmpty()
            when (val result = toolkit.getNodeClientVersion(id))
            {
                is GetNodeClientVersionResult.Ok ->
                    call.respond(
                        NodeClientVersionResponse(
                            nodeId = result.view.nodeId,
                            clientVersion = result.view.clientVersion,
                            path = result.view.path,
                        ),
                    )
                GetNodeClientVersionResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeClientVersionResponse(ok = false, error = "not_found"))
                GetNodeClientVersionResult.ServerNotFound ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        NodeClientVersionResponse(ok = false, error = "server_not_found"),
                    )
                GetNodeClientVersionResult.AgentUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeClientVersionResponse(
                            ok = false,
                            error = "agent_unreachable",
                            message = "Host agent did not answer",
                        ),
                    )
                GetNodeClientVersionResult.InvalidAgentKey ->
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        NodeClientVersionResponse(
                            ok = false,
                            error = InvalidAgentKey.ERROR,
                            message = InvalidAgentKey.MESSAGE,
                        ),
                    )
            }
        }

        post("/api/nodes/{id}/process/stop") {
            val id = call.parameters["id"].orEmpty()
            when (val result = toolkit.controlNodeProcess.stop(id))
            {
                is ControlNodeProcessResult.Ok ->
                    call.respond(
                        NodeProcessControlResponse(
                            nodeId = result.nodeId,
                            pid = result.pid,
                            action = result.action,
                        ),
                    )
                ControlNodeProcessResult.NotFound ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        NodeProcessControlResponse(ok = false, error = "not_found"),
                    )
                ControlNodeProcessResult.ServerNotFound ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        NodeProcessControlResponse(ok = false, error = "server_not_found"),
                    )
                ControlNodeProcessResult.AgentUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeProcessControlResponse(
                            ok = false,
                            error = "agent_unreachable",
                            message = "Host agent did not answer",
                        ),
                    )
                ControlNodeProcessResult.InvalidAgentKey ->
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        NodeProcessControlResponse(
                            ok = false,
                            error = InvalidAgentKey.ERROR,
                            message = InvalidAgentKey.MESSAGE,
                        ),
                    )
                is ControlNodeProcessResult.Failed ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeProcessControlResponse(
                            ok = false,
                            error = result.error,
                            message = result.message,
                        ),
                    )
            }
        }

        post("/api/nodes/{id}/process/start") {
            val id = call.parameters["id"].orEmpty()
            when (val result = toolkit.controlNodeProcess.start(id))
            {
                is ControlNodeProcessResult.Ok ->
                    call.respond(
                        NodeProcessControlResponse(
                            nodeId = result.nodeId,
                            pid = result.pid,
                            action = result.action,
                        ),
                    )
                ControlNodeProcessResult.NotFound ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        NodeProcessControlResponse(ok = false, error = "not_found"),
                    )
                ControlNodeProcessResult.ServerNotFound ->
                    call.respond(
                        HttpStatusCode.NotFound,
                        NodeProcessControlResponse(ok = false, error = "server_not_found"),
                    )
                ControlNodeProcessResult.AgentUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeProcessControlResponse(
                            ok = false,
                            error = "agent_unreachable",
                            message = "Host agent did not answer",
                        ),
                    )
                ControlNodeProcessResult.InvalidAgentKey ->
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        NodeProcessControlResponse(
                            ok = false,
                            error = InvalidAgentKey.ERROR,
                            message = InvalidAgentKey.MESSAGE,
                        ),
                    )
                is ControlNodeProcessResult.Failed ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeProcessControlResponse(
                            ok = false,
                            error = result.error,
                            message = result.message,
                        ),
                    )
            }
        }

        post("/api/nodes") {
            val body = call.receive<NodeUpsertBody>()
            when (val result = toolkit.addNode(body.serverId, body.network, body.env, body.name))
            {
                is AddNodeResult.Created ->
                    call.respond(
                        NodeItemOkResponse(
                            item = result.node.toResponse(toolkit.networkFacts),
                            message = "node saved — awaiting ports",
                        ),
                    )
                AddNodeResult.ServerIdRequired ->
                    call.respond(HttpStatusCode.BadRequest, NodeErrorResponse(error = "server_id_required"))
                AddNodeResult.NetworkRequired ->
                    call.respond(HttpStatusCode.BadRequest, NodeErrorResponse(error = "network_required"))
                AddNodeResult.EnvRequired ->
                    call.respond(HttpStatusCode.BadRequest, NodeErrorResponse(error = "env_required"))
                AddNodeResult.UnknownNetwork ->
                    call.respond(HttpStatusCode.BadRequest, NodeErrorResponse(error = "unknown_network"))
                AddNodeResult.UnknownEnv ->
                    call.respond(HttpStatusCode.BadRequest, NodeErrorResponse(error = "unknown_env"))
                AddNodeResult.ServerNotFound ->
                    call.respond(HttpStatusCode.BadRequest, NodeErrorResponse(error = "server_not_found"))
                AddNodeResult.NoClient ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeErrorResponse(
                            error = "no_client",
                            message = "Add a client for this network and env first",
                        ),
                    )
                is AddNodeResult.AlreadyExists ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        NodeErrorResponse(
                            error = "already_exists",
                            message = "${result.existing.network.value}/${result.existing.env.value} already exists on this server",
                            occupiedNodeId = result.existing.id.value,
                            occupiedStatus = result.existing.status.value,
                        ),
                    )
                is AddNodeResult.OneEnvPerHost ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        NodeErrorResponse(
                            error = "one_env_per_host",
                            message = "${result.occupied.network.value} already has ${result.occupied.network.value}/${result.occupied.env.value} on this server — only one environment per host",
                            occupiedEnv = result.occupied.env.value,
                            occupiedNodeId = result.occupied.id.value,
                        ),
                    )
                AddNodeResult.InsertFailed ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        NodeErrorResponse(error = "insert_failed", message = "Could not save the node"),
                    )
            }
        }

        get("/api/nodes/{id}/ports") {
            val id = call.parameters["id"].orEmpty()
            call.respondNodePorts(toolkit.getNodePorts(id))
        }

        get("/api/nodes/{id}/snapshot") {
            val id = call.parameters["id"].orEmpty()
            call.respondNodeSnapshotPlan(toolkit.getNodeSnapshotPlan(id))
        }

        post("/api/nodes/{id}/snapshot/start") {
            val id = call.parameters["id"].orEmpty()
            val body = runCatching { call.receive<NodeSnapshotStartBody>() }.getOrElse { NodeSnapshotStartBody() }
            call.respondNodeSnapshotStart(toolkit.startNodeSnapshot(id, body.snapshot, body.source))
        }

        post("/api/nodes/{id}/snapshot/probe") {
            val id = call.parameters["id"].orEmpty()
            val body = runCatching { call.receive<NodeSnapshotProbeBody>() }.getOrElse { NodeSnapshotProbeBody() }
            call.respondNodeSnapshotProbe(
                toolkit.probeNodeSnapshotSources(id, body.snapshot, body.sources),
            )
        }

        post("/api/nodes/{id}/snapshot/stop") {
            val id = call.parameters["id"].orEmpty()
            call.respondNodeSnapshotStop(toolkit.stopNodeSnapshot(id))
        }

        get("/api/nodes/{id}/snapshot/progress") {
            val id = call.parameters["id"].orEmpty()
            call.respondNodeSnapshotProgress(toolkit.getNodeSnapshotProgress(id))
        }

        post("/api/nodes/status") {
            val body = call.receive<NodeStatusBody>()
            call.respondNodeStatus(toolkit.updateNodeStatus(body.id, body.status))
        }

        post("/api/nodes/remove") {
            val body = call.receive<NodeRemoveBody>()
            val mode = RemoveNodeMode.parse(body.mode)
            if (mode == null)
            {
                call.respond(HttpStatusCode.BadRequest, NodeErrorResponse(error = "unknown_mode"))
                return@post
            }
            when (val result = toolkit.removeNode(body.id, mode))
            {
                is RemoveNodeResult.Removed ->
                    call.respond(
                        NodeRemoveResponse(
                            id = result.node.id.value,
                            panelOnly = result.mode == RemoveNodeMode.PANEL,
                            message = when (result.mode)
                            {
                                RemoveNodeMode.PANEL -> "removed from panel — host was not changed"
                                RemoveNodeMode.AGENTS -> "unit removed on host — chain data kept"
                                RemoveNodeMode.WIPE -> "unit removed and chain data wiped on host"
                            },
                        ),
                    )
                RemoveNodeResult.NotFound ->
                    call.respond(HttpStatusCode.NotFound, NodeErrorResponse(error = "not_found"))
                RemoveNodeResult.ServerNotFound ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NodeErrorResponse(error = "server_not_found", message = "Server not found"),
                    )
                RemoveNodeResult.AgentUnreachable ->
                    call.respond(
                        HttpStatusCode.BadGateway,
                        NodeErrorResponse(
                            error = "agent_unreachable",
                            message = "Host agent did not answer",
                        ),
                    )
                RemoveNodeResult.InvalidAgentKey ->
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        NodeErrorResponse(
                            error = InvalidAgentKey.ERROR,
                            message = InvalidAgentKey.MESSAGE,
                        ),
                    )
                is RemoveNodeResult.Failed ->
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        NodeErrorResponse(error = result.error, message = result.message),
                    )
            }
        }
    }
}

private fun ClientUpdateInfo.toResponse() = NodeClientUpdateInfoResponse(
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
    events = events.map {
        NodeClientUpdateEventResponse(
            id = it.id,
            label = it.label,
            detail = it.detail,
            at = it.at,
        )
    },
)

private fun Node.toResponse(facts: NetworkFactsRepository) = NodeItemResponse(
    id = id.value,
    serverId = serverId.value,
    name = name,
    network = network.value,
    env = env.value,
    publicPort = publicPort,
    agentPort = agentPort,
    nodeHttpPort = nodeHttpPort,
    p2pPort = p2pPort,
    status = status.value,
    height = height,
    heightAt = heightAt,
    networkHeight = networkHeight,
    syncPct = syncPct,
    sizeOnDisk = sizeOnDisk,
    clientVersion = clientVersion,
    clientLatest = clientLatest,
    clientUpdateAvailable = clientUpdateAvailable,
    needsSnapshot = nodeNeedsSnapshot(this, facts),
    diskLayout = parseDiskLayoutJson(diskLayoutJson),
    installOptions = parseDiskLayoutJson(installOptionsJson),
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun ClientConfigFacts.toResponse() = ClientConfigResponse(
    program = program,
    format = format,
    template = template,
    templates = templates,
    envSections = envSections,
    bindings = bindings.map {
        ClientConfigBindingResponse(
            path = it.path,
            source = it.source,
            description = it.description,
            role = it.role,
            option = it.option,
            value = it.value,
            relative = it.relative,
            optional = it.optional,
            default = it.default,
            map = it.map,
            whenInstallOption = it.whenInstallOption,
            whenInstallOptionValue = it.whenInstallOptionValue,
            testConnect = it.testConnect?.let { tc ->
                ClientConfigTestConnectResponse(
                    kind = tc.kind,
                    label = tc.label,
                    help = tc.help,
                )
            },
        )
    },
)
