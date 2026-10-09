package rpcnode.toolkit.panel.clients.presentation.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.application.ClientRowView
import rpcnode.toolkit.clients.application.add.AddClientResult
import rpcnode.toolkit.clients.application.delete.DeleteClientResult
import rpcnode.toolkit.clients.application.list.ClientsStats
import rpcnode.toolkit.clients.application.probe.ProbeClientsResult
import rpcnode.toolkit.clients.application.sync.SyncClientsResult
import rpcnode.toolkit.clients.application.version.ResolveClientReleaseResult
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.wiring.Toolkit

@Serializable
data class ClientRowResponse(
    val network: String,
    val env: String,
    val program: String = "",
    val pin: String = "",
    val tag: String = "",
    val latest: String = "",
    @SerialName("latest_tag") val latestTag: String = "",
    val status: String = "",
    val source: String = "",
    val notes: String = "",
    @SerialName("skip_reason") val skipReason: String = "",
    val url: String = "",
    @SerialName("probe_error") val probeError: String = "",
    @SerialName("probed_at") val probedAt: String = "",
    @SerialName("download_phase") val downloadPhase: String? = null,
    @SerialName("download_name") val downloadName: String? = null,
    @SerialName("download_bytes") val downloadBytes: Long? = null,
    @SerialName("download_total") val downloadTotal: Long? = null,
    @SerialName("download_pct") val downloadPct: Double? = null,
    @SerialName("download_error") val downloadError: String? = null,
)

@Serializable
data class ClientsStatsResponse(
    val total: Int = 0,
    val stale: Int = 0,
    val fail: Int = 0,
    val missing: Int = 0,
    val deleted: Int = 0,
)

@Serializable
data class ClientsResponse(
    val ok: Boolean = true,
    val writer: String = "toolkit.db",
    val source: String = "programs",
    val writable: Boolean = true,
    val probing: Boolean = false,
    @SerialName("probed_at") val probedAt: String? = null,
    val error: String? = null,
    @SerialName("github_token_set") val githubTokenSet: Boolean = false,
    val dest: String = "",
    val rows: List<ClientRowResponse> = emptyList(),
    val want: Int? = null,
    val stats: ClientsStatsResponse? = null,
    val network: String? = null,
    val env: String? = null,
)

@Serializable
data class ClientsErrorResponse(
    val ok: Boolean = false,
    val error: String,
    val message: String? = null,
)

@Serializable
data class ClientAddBody(
    val network: String = "",
    val env: String = "",
)

@Serializable
data class AddClientResponse(
    val ok: Boolean = true,
    val network: String = "",
    val env: String = "",
    val probe: String = "",
    val error: String? = null,
)

@Serializable
data class ClientActionBody(
    val network: String? = null,
    val env: String? = null,
    val program: String? = null,
    val force: Boolean = false,
)

@Serializable
data class ClientsActionResponse(
    val ok: Boolean = true,
    val started: Boolean = true,
)

@Serializable
data class ClientDeleteBody(
    val network: String = "",
    val env: String? = null,
)

@Serializable
data class ClientReleaseResponse(
    val ok: Boolean = true,
    val version: String? = null,
    val tag: String? = null,
    val source: String? = null,
)

@Serializable
data class DeleteClientResponse(
    val ok: Boolean = true,
    val purged: Boolean = false,
    val dest: String = "",
    val removed: List<String> = emptyList(),
)

fun Application.clientsApiRoutes(toolkit: Toolkit)
{
    routing {
        get("/api/clients") {
            val result = toolkit.listClients()
            call.respond(
                ClientsResponse(
                    probedAt = result.probedAt,
                    githubTokenSet = toolkit.githubTokenProvider.current() != null,
                    dest = toolkit.clientsDestDir.toAbsolutePath().toString(),
                    rows = result.rows.map { it.toResponse() },
                    stats = result.stats.toResponse(),
                ),
            )
        }

        get("/api/clients/preview") {
            val networkRaw = call.request.queryParameters["network"].orEmpty()
            val envRaw = call.request.queryParameters["env"].orEmpty()
            val network = NetworkId.parse(networkRaw)
            val env = network?.let { EnvId.parse(envRaw) }
            if (network == null || env == null)
            {
                call.respond(HttpStatusCode.BadRequest, ClientsErrorResponse(error = "network and env required"))
                return@get
            }
            val result = toolkit.previewClients(network, env)
            call.respond(
                ClientsResponse(
                    network = network.value,
                    env = env.value,
                    rows = result.rows.map { it.toResponse() },
                    want = result.want,
                ),
            )
        }

        get("/api/clients/version") {
            val network = call.request.queryParameters["network"].orEmpty()
            val env = call.request.queryParameters["env"].orEmpty()
            when (val result = toolkit.resolveClientRelease(network, env))
            {
                is ResolveClientReleaseResult.Resolved ->
                    call.respond(
                        ClientReleaseResponse(
                            version = result.release?.version,
                            tag = result.release?.tag,
                            source = result.release?.sourceLabel,
                        ),
                    )
                ResolveClientReleaseResult.UnknownNetwork ->
                    call.respond(HttpStatusCode.BadRequest, ClientsErrorResponse(error = "unknown_network"))
                ResolveClientReleaseResult.UnknownEnv ->
                    call.respond(HttpStatusCode.BadRequest, ClientsErrorResponse(error = "unknown_env"))
            }
        }

        post("/api/clients") {
            val body = call.receive<ClientAddBody>()
            when (val result = toolkit.addClient(body.network, body.env))
            {
                is AddClientResult.Ok -> call.respond(
                    AddClientResponse(
                        network = result.network.value,
                        env = result.env.value,
                        probe = if (result.probeQueued) "queued" else "need_token",
                    ),
                )
                AddClientResult.UnknownNetwork ->
                    call.respond(HttpStatusCode.BadRequest, ClientsErrorResponse(error = "unknown network"))
                AddClientResult.UnknownEnv ->
                    call.respond(HttpStatusCode.BadRequest, ClientsErrorResponse(error = "unknown env"))
            }
        }

        post("/api/clients/probe") {
            val body = runCatching { call.receive<ClientActionBody>() }.getOrDefault(ClientActionBody())
            when (toolkit.probeClients(body.network, body.env, body.program))
            {
                ProbeClientsResult.Done ->
                    call.respond(ClientsActionResponse())
                ProbeClientsResult.TokenRequired ->
                    call.respond(HttpStatusCode.Conflict, githubTokenRequiredResponse())
            }
        }

        post("/api/clients/sync") {
            val body = runCatching { call.receive<ClientActionBody>() }.getOrDefault(ClientActionBody())
            when (toolkit.syncClients(body.network, body.env, body.program, body.force))
            {
                SyncClientsResult.Started ->
                    call.respond(HttpStatusCode.Accepted, ClientsActionResponse())
                SyncClientsResult.TokenRequired ->
                    call.respond(HttpStatusCode.Conflict, githubTokenRequiredResponse())
            }
        }

        post("/api/clients/delete") {
            val body = call.receive<ClientDeleteBody>()
            if (body.network.isBlank())
            {
                call.respond(HttpStatusCode.BadRequest, ClientsErrorResponse(error = "network required"))
                return@post
            }
            when (val result = toolkit.deleteClient(body.network, body.env))
            {
                is DeleteClientResult.Ok -> call.respond(
                    DeleteClientResponse(purged = result.purged, dest = result.dest, removed = result.removed),
                )
                is DeleteClientResult.Failed ->
                    call.respond(HttpStatusCode.BadGateway, ClientsErrorResponse(error = result.error))
            }
        }
    }
}

private fun githubTokenRequiredResponse() = ClientsErrorResponse(
    error = "github_token_required",
    message = "Set a GitHub personal access token in Settings first.",
)

private fun ClientVersionPin.toResponse() = ClientRowResponse(
    network = network.value,
    env = env.value,
    program = program,
    pin = currentVersion,
    tag = currentTag,
    latest = latestVersion,
    latestTag = latestTag,
    status = status.value,
    source = source,
    notes = notes,
    skipReason = skipReason,
    url = url,
    probeError = probeError,
    probedAt = probedAt,
)

private fun ClientRowView.toResponse() = pin.toResponse().copy(
    downloadPhase = downloadPhase,
    downloadName = downloadName,
    downloadBytes = downloadBytes,
    downloadTotal = downloadTotal,
    downloadPct = downloadPct,
    downloadError = downloadError,
)

private fun ClientsStats.toResponse() = ClientsStatsResponse(
    total = total,
    stale = stale,
    fail = fail,
    missing = missing,
    deleted = deleted,
)
