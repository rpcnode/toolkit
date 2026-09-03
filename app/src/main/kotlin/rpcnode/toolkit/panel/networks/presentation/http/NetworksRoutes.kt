package rpcnode.toolkit.panel.networks.presentation.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.networks.application.connect.ListEthereumNodesUseCase
import rpcnode.toolkit.networks.application.connect.ListL1ParentChoicesUseCase
import rpcnode.toolkit.networks.application.connect.TestConfigConnectUseCase
import rpcnode.toolkit.networks.application.install.CheckNetworkInstallResult
import rpcnode.toolkit.networks.application.list.NetworkListItem
import rpcnode.toolkit.networks.application.setstatus.SetNetworkStatusResult
import rpcnode.toolkit.networks.application.snapshot.ListSnapshotSourcesUseCase
import rpcnode.toolkit.networks.application.snapshot.PreferCdnSnapshotResult
import rpcnode.toolkit.networks.application.snapshot.SnapshotSourcesResult
import rpcnode.toolkit.panel.nodes.presentation.http.SnapshotSourceResponse
import rpcnode.toolkit.networks.domain.model.NetworkFacts
import rpcnode.toolkit.panel.nodes.presentation.http.ClientConfigResponse
import rpcnode.toolkit.panel.nodes.presentation.http.toResponse
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.wiring.Toolkit

@Serializable
data class NetworkEnvDetailResponse(
    val id: String,
    val label: String? = null,
    @SerialName("disk_hint_gib") val diskHintGiB: Double? = null,
    @SerialName("full_node_gib") val fullNodeGiB: Double? = null,
    @SerialName("archive_gib") val archiveGiB: Double? = null,
    @SerialName("cpu_cores") val cpuCores: Double? = null,
    @SerialName("memory_gib") val memoryGiB: Double? = null,
    val snapshot: String? = null,
    @SerialName("l1_rpc_url") val l1RpcUrl: String? = null,
    @SerialName("l1_beacon_url") val l1BeaconUrl: String? = null,
    @SerialName("l1_pick_help") val l1PickHelp: String? = null,
)

@Serializable
data class NetworkDiskRoleResponse(
    val id: String,
    val label: String,
    val media: String,
)

@Serializable
data class NetworkItemResponse(
    val id: String,
    val label: String,
    val envs: List<String>,
    val enabled: Boolean = false,
    val status: String = "",
    @SerialName("files_ready") val filesReady: Boolean = false,
    @SerialName("pin_only") val pinOnly: Boolean = false,
    @SerialName("env_details") val envDetails: List<NetworkEnvDetailResponse> = emptyList(),
    @SerialName("disk_roles") val diskRoles: List<NetworkDiskRoleResponse> = emptyList(),
    @SerialName("disk_media") val diskMedia: String? = null,
    @SerialName("disk_notes") val diskNotes: List<String> = emptyList(),
    @SerialName("one_env_per_host") val oneEnvPerHost: Boolean = false,
    @SerialName("client_config") val clientConfig: ClientConfigResponse? = null,
)

@Serializable
data class NetworksListResponse(
    val ok: Boolean = true,
    val items: List<NetworkItemResponse>,
)

@Serializable
data class NetworkErrorResponse(
    val ok: Boolean = false,
    val error: String,
    val message: String? = null,
)

@Serializable
data class NetworkActionBody(
    val network: String = "",
    val action: String = "",
)

@Serializable
data class NetworkActionResponse(
    val ok: Boolean = true,
    val network: String,
    val status: String,
)

@Serializable
data class NetworkInstallBody(
    val network: String = "",
    val env: String? = null,
)

@Serializable
data class NetworkInstallResponse(
    val ok: Boolean = true,
    val network: String,
    val status: String,
    val source: String,
    @SerialName("pin_only") val pinOnly: Boolean,
    val message: String,
)

@Serializable
data class NetworkRemoveResponse(
    val ok: Boolean = true,
    val removed: String,
)

@Serializable
data class NetworkTestConnectBody(
    val kind: String = "",
    val url: String = "",
)

@Serializable
data class NetworkTestConnectResponse(
    val ok: Boolean = true,
    val detail: String? = null,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class L1ParentChoiceResponse(
    val id: String,
    val kind: String,
    val label: String,
    val rpc: String,
    val beacon: String,
    val status: String? = null,
    @SerialName("same_host") val sameHost: Boolean = false,
    @SerialName("node_id") val nodeId: String? = null,
    @SerialName("server_id") val serverId: String? = null,
)

@Serializable
data class L1ParentChoicesResponse(
    val ok: Boolean = true,
    @SerialName("l1_env") val l1Env: String? = null,
    @SerialName("pick_help") val pickHelp: String? = null,
    val choices: List<L1ParentChoiceResponse> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class EthereumPublicEndpointResponse(
    val label: String,
    val rpc: String,
    val beacon: String = "",
)

@Serializable
data class EthereumNodeItemResponse(
    val id: String,
    val name: String,
    val env: String,
    val status: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("same_host") val sameHost: Boolean = false,
    val rpc: String,
    val beacon: String,
    @SerialName("public_endpoint") val publicEndpoint: String? = null,
)

@Serializable
data class EthereumNodesResponse(
    val ok: Boolean = true,
    val env: String? = null,
    val public: EthereumPublicEndpointResponse? = null,
    val items: List<EthereumNodeItemResponse> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class SnapshotResponse(
    val ok: Boolean = true,
    val url: String? = null,
    @SerialName("official_url") val officialUrl: String? = null,
    val version: String? = null,
    val source: String? = null,
    @SerialName("stream_unpack") val streamUnpack: Boolean? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("type_id") val typeId: String? = null,
    val sources: List<SnapshotSourceResponse> = emptyList(),
    @SerialName("default_source_id") val defaultSourceId: String? = null,
    val error: String? = null,
    val message: String? = null,
)

fun Application.networksApiRoutes(toolkit: Toolkit)
{
    val testConnect = TestConfigConnectUseCase(SimpleHttp())
    routing {
        get("/api/networks") {
            val all = call.request.queryParameters["all"] == "1"
            val items = toolkit.listNetworks(all)
            call.respond(NetworksListResponse(items = items.map { it.toResponse() }))
        }

        post("/api/networks/test-connect") {
            val body = call.receive<NetworkTestConnectBody>()
            when (val result = testConnect(body.kind, body.url))
            {
                is TestConfigConnectUseCase.Result.Ok ->
                    call.respond(NetworkTestConnectResponse(detail = result.detail))
                is TestConfigConnectUseCase.Result.Failed ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NetworkTestConnectResponse(
                            ok = false,
                            error = "connect_failed",
                            message = result.detail,
                        ),
                    )
                TestConfigConnectUseCase.Result.BadKind ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NetworkTestConnectResponse(
                            ok = false,
                            error = "bad_kind",
                            message = "unknown testConnect.kind — use eth_rpc or beacon_genesis",
                        ),
                    )
                TestConfigConnectUseCase.Result.BadUrl ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        NetworkTestConnectResponse(
                            ok = false,
                            error = "bad_url",
                            message = "url must be http:// or https://",
                        ),
                    )
            }
        }

        get("/api/networks/ethereum/nodes") {
            val env = call.request.queryParameters["env"].orEmpty()
            val status = call.request.queryParameters["status"] ?: "active"
            val serverId = call.request.queryParameters["server_id"]?.trim()?.ifEmpty { null }
            when (val result = toolkit.listEthereumNodes(env, status, serverId))
            {
                is ListEthereumNodesUseCase.Result.Ready ->
                    call.respond(
                        EthereumNodesResponse(
                            env = result.value.env,
                            public = result.value.public?.let {
                                EthereumPublicEndpointResponse(
                                    label = it.label,
                                    rpc = it.rpc,
                                    beacon = it.beacon,
                                )
                            },
                            items = result.value.items.map {
                                EthereumNodeItemResponse(
                                    id = it.id,
                                    name = it.name,
                                    env = it.env,
                                    status = it.status,
                                    serverId = it.serverId,
                                    sameHost = it.sameHost,
                                    rpc = it.rpc,
                                    beacon = it.beacon,
                                    publicEndpoint = it.publicEndpoint,
                                )
                            },
                        ),
                    )
                ListEthereumNodesUseCase.Result.UnknownEnv ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        EthereumNodesResponse(ok = false, error = "unknown_env"),
                    )
            }
        }

        get("/api/networks/l1-parents") {
            val forNetwork = call.request.queryParameters["network"].orEmpty()
            val env = call.request.queryParameters["env"].orEmpty()
            val serverId = call.request.queryParameters["server_id"]?.trim()?.ifEmpty { null }
            when (val result = toolkit.listL1ParentChoices(forNetwork, env, serverId))
            {
                is ListL1ParentChoicesUseCase.Result.Ready ->
                    call.respond(
                        L1ParentChoicesResponse(
                            l1Env = result.value.l1Env,
                            pickHelp = result.value.pickHelp,
                            choices = result.value.choices.map {
                                L1ParentChoiceResponse(
                                    id = it.id,
                                    kind = it.kind,
                                    label = it.label,
                                    rpc = it.rpc,
                                    beacon = it.beacon,
                                    status = it.status,
                                    sameHost = it.sameHost,
                                    nodeId = it.nodeId,
                                    serverId = it.serverId,
                                )
                            },
                        ),
                    )
                ListL1ParentChoicesUseCase.Result.NotApplicable ->
                    call.respond(
                        L1ParentChoicesResponse(
                            ok = false,
                            error = "not_applicable",
                            message = "network has no L1 parent",
                        ),
                    )
                ListL1ParentChoicesUseCase.Result.UnknownNetwork ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        L1ParentChoicesResponse(ok = false, error = "unknown_network"),
                    )
                ListL1ParentChoicesUseCase.Result.UnknownEnv ->
                    call.respond(
                        HttpStatusCode.BadRequest,
                        L1ParentChoicesResponse(ok = false, error = "unknown_env"),
                    )
            }
        }

        get("/api/networks/snapshot") {
            val network = call.request.queryParameters["network"].orEmpty()
            val env = call.request.queryParameters["env"].orEmpty()
            val source = call.request.queryParameters["source"]
            val type = call.request.queryParameters["type"].orEmpty()
            when (val listed = toolkit.listSnapshotSources(network, env, type))
            {
                SnapshotSourcesResult.UnknownNetwork ->
                    call.respond(HttpStatusCode.BadRequest, NetworkErrorResponse(error = "unknown_network"))
                SnapshotSourcesResult.UnknownEnv ->
                    call.respond(HttpStatusCode.BadRequest, NetworkErrorResponse(error = "unknown_env"))
                is SnapshotSourcesResult.Resolved ->
                {
                    val sources = listed.sources.map {
                        SnapshotSourceResponse(
                            id = it.id,
                            label = it.label,
                            url = it.url,
                            version = it.version,
                            sizeBytes = it.sizeBytes,
                            streamUnpack = it.streamUnpack,
                            available = it.available,
                            detail = it.detail,
                        )
                    }
                    when (val picked = toolkit.preferCdnSnapshot(network, env, source, type))
                    {
                        is PreferCdnSnapshotResult.Resolved ->
                            call.respond(
                                SnapshotResponse(
                                    url = picked.url,
                                    officialUrl = picked.officialUrl,
                                    version = picked.version,
                                    source = picked.source,
                                    streamUnpack = picked.streamUnpack,
                                    sizeBytes = picked.sizeBytes,
                                    typeId = picked.typeId,
                                    sources = sources,
                                    defaultSourceId = listed.defaultSourceId,
                                ),
                            )
                        is PreferCdnSnapshotResult.SourceUnavailable ->
                            call.respond(
                                HttpStatusCode.BadRequest,
                                SnapshotResponse(
                                    ok = false,
                                    officialUrl = listed.officialUrl,
                                    version = listed.officialVersion,
                                    typeId = listed.typeId,
                                    sources = sources,
                                    defaultSourceId = listed.defaultSourceId,
                                    error = "source_unavailable",
                                    message = picked.detail,
                                ),
                            )
                        PreferCdnSnapshotResult.UnknownNetwork ->
                            call.respond(HttpStatusCode.BadRequest, NetworkErrorResponse(error = "unknown_network"))
                        PreferCdnSnapshotResult.UnknownEnv ->
                            call.respond(HttpStatusCode.BadRequest, NetworkErrorResponse(error = "unknown_env"))
                    }
                }
            }
        }

        post("/api/networks") {
            val body = call.receive<NetworkActionBody>()
            when (val result = toolkit.setNetworkStatus(body.network, body.action))
            {
                is SetNetworkStatusResult.Ok ->
                    call.respond(NetworkActionResponse(network = result.network.value, status = result.status.value))
                SetNetworkStatusResult.UnknownNetwork ->
                    call.respond(HttpStatusCode.BadRequest, NetworkErrorResponse(error = "unknown_network"))
                SetNetworkStatusResult.BadAction ->
                    call.respond(HttpStatusCode.BadRequest, NetworkErrorResponse(error = "bad_action"))
            }
        }

        post("/api/networks/install") {
            val body = call.receive<NetworkInstallBody>()
            when (val result = toolkit.checkNetworkInstall(body.network))
            {
                is CheckNetworkInstallResult.FilesOk ->
                    call.respond(
                        NetworkInstallResponse(
                            network = result.network.value,
                            status = "files_ok",
                            source = result.source,
                            pinOnly = result.pinOnly,
                            message = "Install files ready (or host pin — no CDN package).",
                        ),
                    )
                CheckNetworkInstallResult.UnknownNetwork ->
                    call.respond(HttpStatusCode.BadRequest, NetworkErrorResponse(error = "unknown_network"))
                CheckNetworkInstallResult.ClientRequired ->
                    call.respond(
                        HttpStatusCode.Conflict,
                        NetworkErrorResponse(
                            error = "client_required",
                            message = "Download the client on Clients first (GitHub token in Settings), then Add network.",
                        ),
                    )
            }
        }

        delete("/api/networks/{id}") {
            val id = call.parameters["id"].orEmpty()
            val removed = toolkit.removeNetwork(id)
            if (removed == null)
            {
                call.respond(HttpStatusCode.BadRequest, NetworkErrorResponse(error = "bad_id"))
            }
            else
            {
                call.respond(NetworkRemoveResponse(removed = removed.value))
            }
        }
    }
}

private fun NetworkListItem.toResponse() = NetworkItemResponse(
    id = id.value,
    label = label,
    envs = envs.map { it.value },
    enabled = enabled,
    status = status?.value.orEmpty(),
    filesReady = filesReady,
    pinOnly = pinOnly,
    envDetails = envDetailsResponse(envs, facts),
    diskRoles = facts?.diskRoles.orEmpty().map { NetworkDiskRoleResponse(it.id, it.label, it.media) },
    diskMedia = facts?.diskMedia,
    diskNotes = facts?.diskNotes.orEmpty(),
    oneEnvPerHost = facts?.oneEnvPerHost ?: false,
    clientConfig = facts?.clientConfig?.toResponse(),
)

/** One [NetworkEnvDetailResponse] per catalog env id, in catalog order — facts fill in what they have, rest stays null. */
private fun envDetailsResponse(
    envIds: List<EnvId>,
    facts: NetworkFacts?,
): List<NetworkEnvDetailResponse>
{
    val byId = facts?.envs.orEmpty().associateBy { it.id }
    return envIds.map { envId ->
        val e = byId[envId.value]
        NetworkEnvDetailResponse(
            id = envId.value,
            label = e?.label,
            diskHintGiB = e?.diskHintGiB,
            fullNodeGiB = e?.fullNodeGiB,
            archiveGiB = e?.archiveGiB,
            cpuCores = e?.cpuCores,
            memoryGiB = e?.memoryGiB,
            snapshot = e?.snapshot,
            l1RpcUrl = e?.l1RpcUrl,
            l1BeaconUrl = e?.l1BeaconUrl,
            l1PickHelp = e?.l1PickHelp,
        )
    }
}
