package rpcnode.toolkit.panel.nodes.presentation.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rpcnode.toolkit.networks.application.snapshot.SnapshotSourceOption
import rpcnode.toolkit.nodes.application.snapshot.NodeSnapshotPlanResult
import rpcnode.toolkit.nodes.application.snapshot.NodeSnapshotProgressResult
import rpcnode.toolkit.nodes.application.snapshot.ProbeNodeSnapshotSourcesResult
import rpcnode.toolkit.nodes.application.snapshot.StartNodeSnapshotResult
import rpcnode.toolkit.nodes.application.snapshot.StopNodeSnapshotResult
import rpcnode.toolkit.nodes.application.status.UpdateNodeStatusResult

@Serializable
data class NodeStatusBody(
    val id: String = "",
    val status: String = "",
)

@Serializable
data class NodeStatusResponse(
    val ok: Boolean = true,
    val status: String = "",
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class SnapshotTypeResponse(
    val id: String = "",
    val kind: String = "",
    val label: String = "",
    val hint: String? = null,
    @SerialName("disk_gib") val diskGiB: Double? = null,
    val default: Boolean = false,
)

@Serializable
data class SnapshotSourceResponse(
    val id: String = "",
    val label: String = "",
    val url: String? = null,
    val version: String? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("stream_unpack") val streamUnpack: Boolean? = null,
    val available: Boolean = false,
    val detail: String? = null,
)

@Serializable
data class NodeSnapshotPlanResponse(
    val ok: Boolean = true,
    val url: String? = null,
    @SerialName("official_url") val officialUrl: String? = null,
    val version: String? = null,
    val source: String? = null,
    @SerialName("stream_unpack") val streamUnpack: Boolean? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("dest_dir") val destDir: String? = null,
    val status: String? = null,
    @SerialName("type_id") val typeId: String? = null,
    @SerialName("snapshot_types") val snapshotTypes: List<SnapshotTypeResponse> = emptyList(),
    val sources: List<SnapshotSourceResponse> = emptyList(),
    @SerialName("default_source_id") val defaultSourceId: String? = null,
    /** Chain process downloads the snapshot (Agave / via-node); no toolkit CDN URL. */
    @SerialName("via_node") val viaNode: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class NodeSnapshotProgressResponse(
    val ok: Boolean = true,
    val pct: Double? = null,
    val phase: String = "",
    val detail: String = "",
    val ready: Boolean = false,
    val failed: Boolean = false,
    val error: String? = null,
    val status: String? = null,
    val message: String? = null,
    @SerialName("log_tail") val logTail: List<String> = emptyList(),
)

@Serializable
data class NodeSnapshotStartBody(
    val snapshot: String? = null,
    val source: String? = null,
)

@Serializable
data class NodeSnapshotProbeBody(
    val snapshot: String? = null,
    val sources: List<String>? = null,
)

@Serializable
data class SnapshotSourceSpeedResponse(
    val id: String = "",
    val available: Boolean = false,
    @SerialName("bytes_per_sec") val bytesPerSec: Long? = null,
    @SerialName("sample_bytes") val sampleBytes: Long? = null,
    @SerialName("latency_ms") val latencyMs: Long? = null,
    val detail: String? = null,
)

@Serializable
data class NodeSnapshotProbeResponse(
    val ok: Boolean = true,
    val results: List<SnapshotSourceSpeedResponse> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class NodeSnapshotActionResponse(
    val ok: Boolean = true,
    @SerialName("type_id") val typeId: String? = null,
    val url: String? = null,
    @SerialName("dest_dir") val destDir: String? = null,
    val error: String? = null,
    val message: String? = null,
)

suspend fun ApplicationCall.respondNodeStatus(result: UpdateNodeStatusResult)
{
    when (result)
    {
        is UpdateNodeStatusResult.Ok ->
            respond(NodeStatusResponse(status = result.status.value))
        UpdateNodeStatusResult.InvalidStatus ->
            respond(HttpStatusCode.BadRequest, NodeStatusResponse(ok = false, error = "invalid_status"))
        UpdateNodeStatusResult.NotFound ->
            respond(HttpStatusCode.NotFound, NodeStatusResponse(ok = false, error = "not_found"))
    }
}

suspend fun ApplicationCall.respondNodeSnapshotPlan(result: NodeSnapshotPlanResult)
{
    when (result)
    {
        is NodeSnapshotPlanResult.Ok ->
            respond(
                NodeSnapshotPlanResponse(
                    url = result.url,
                    officialUrl = result.officialUrl,
                    version = result.version,
                    source = result.source,
                    streamUnpack = result.streamUnpack,
                    sizeBytes = result.sizeBytes,
                    destDir = result.destDir,
                    status = result.status,
                    typeId = result.typeId,
                    snapshotTypes = result.snapshotTypes.map {
                        SnapshotTypeResponse(
                            id = it.id,
                            kind = it.kind,
                            label = it.label,
                            hint = it.hint,
                            diskGiB = it.diskGiB,
                            default = it.default,
                        )
                    },
                    sources = result.sources.map { it.toResponse() },
                    defaultSourceId = result.defaultSourceId,
                    viaNode = result.viaNode,
                ),
            )
        NodeSnapshotPlanResult.NotFound ->
            respond(HttpStatusCode.NotFound, NodeSnapshotPlanResponse(ok = false, error = "not_found"))
        NodeSnapshotPlanResult.NoSnapshot ->
            respond(
                NodeSnapshotPlanResponse(
                    ok = false,
                    error = "no_snapshot",
                    message = "This network/env does not use snapshots",
                ),
            )
        NodeSnapshotPlanResult.MissingDest ->
            respond(
                HttpStatusCode.BadRequest,
                NodeSnapshotPlanResponse(
                    ok = false,
                    error = "missing_dest",
                    message = "Pick a disk layout before downloading the snapshot",
                ),
            )
    }
}

suspend fun ApplicationCall.respondNodeSnapshotStart(result: StartNodeSnapshotResult)
{
    when (result)
    {
        is StartNodeSnapshotResult.Ok ->
            respond(
                NodeSnapshotActionResponse(
                    typeId = result.typeId,
                    url = result.url,
                    destDir = result.destDir,
                ),
            )
        StartNodeSnapshotResult.NotFound ->
            respond(HttpStatusCode.NotFound, NodeSnapshotActionResponse(ok = false, error = "not_found"))
        StartNodeSnapshotResult.NoSnapshot ->
            respond(
                HttpStatusCode.BadRequest,
                NodeSnapshotActionResponse(ok = false, error = "no_snapshot"),
            )
        StartNodeSnapshotResult.MissingUrl ->
            respond(
                HttpStatusCode.BadRequest,
                NodeSnapshotActionResponse(ok = false, error = "missing_url", message = "Snapshot URL is not configured"),
            )
        StartNodeSnapshotResult.MissingDest ->
            respond(
                HttpStatusCode.BadRequest,
                NodeSnapshotActionResponse(
                    ok = false,
                    error = "missing_dest",
                    message = "Pick a disk layout before downloading the snapshot",
                ),
            )
        StartNodeSnapshotResult.InvalidType ->
            respond(
                HttpStatusCode.BadRequest,
                NodeSnapshotActionResponse(
                    ok = false,
                    error = "invalid_type",
                    message = "Unknown snapshot type for this network/env",
                ),
            )
        StartNodeSnapshotResult.ServerNotFound ->
            respond(HttpStatusCode.BadRequest, NodeSnapshotActionResponse(ok = false, error = "server_not_found"))
        StartNodeSnapshotResult.AgentUnreachable ->
            respond(
                NodeSnapshotActionResponse(
                    ok = false,
                    error = "agent_unreachable",
                    message = "Host agent did not answer — cannot start snapshot download",
                ),
            )
        is StartNodeSnapshotResult.SourceUnavailable ->
            respond(
                HttpStatusCode.BadRequest,
                NodeSnapshotActionResponse(
                    ok = false,
                    error = "source_unavailable",
                    message = result.detail,
                ),
            )
        is StartNodeSnapshotResult.AlreadyRunning ->
            respond(
                NodeSnapshotActionResponse(
                    ok = true,
                    typeId = result.typeId,
                    url = result.url,
                    destDir = result.destDir,
                    message = "Snapshot download is already running on the host",
                ),
            )
    }
}

suspend fun ApplicationCall.respondNodeSnapshotStop(result: StopNodeSnapshotResult)
{
    when (result)
    {
        StopNodeSnapshotResult.Ok ->
            respond(NodeSnapshotActionResponse(message = "Snapshot stopped — files removed"))
        StopNodeSnapshotResult.NotFound ->
            respond(HttpStatusCode.NotFound, NodeSnapshotActionResponse(ok = false, error = "not_found"))
        StopNodeSnapshotResult.ServerNotFound ->
            respond(HttpStatusCode.BadRequest, NodeSnapshotActionResponse(ok = false, error = "server_not_found"))
        StopNodeSnapshotResult.AgentUnreachable ->
            respond(
                NodeSnapshotActionResponse(
                    ok = false,
                    error = "agent_unreachable",
                    message = "Host agent did not answer — cannot stop snapshot download",
                ),
            )
    }
}

suspend fun ApplicationCall.respondNodeSnapshotProgress(result: NodeSnapshotProgressResult)
{
    when (result)
    {
        is NodeSnapshotProgressResult.Ok ->
            respond(
                NodeSnapshotProgressResponse(
                    pct = result.pct,
                    phase = result.phase,
                    detail = result.detail,
                    ready = result.ready,
                    failed = result.failed,
                    error = result.error.ifBlank { null },
                    status = result.status,
                    logTail = result.logTail,
                ),
            )
        NodeSnapshotProgressResult.NotFound ->
            respond(HttpStatusCode.NotFound, NodeSnapshotProgressResponse(ok = false, error = "not_found"))
        NodeSnapshotProgressResult.ServerNotFound ->
            respond(HttpStatusCode.BadRequest, NodeSnapshotProgressResponse(ok = false, error = "server_not_found"))
        NodeSnapshotProgressResult.AgentUnreachable ->
            respond(
                NodeSnapshotProgressResponse(
                    ok = false,
                    error = "agent_unreachable",
                    message = "Host agent did not answer — cannot read snapshot progress",
                ),
            )
    }
}

suspend fun ApplicationCall.respondNodeSnapshotProbe(result: ProbeNodeSnapshotSourcesResult)
{
    when (result)
    {
        is ProbeNodeSnapshotSourcesResult.Ok ->
            respond(
                NodeSnapshotProbeResponse(
                    results = result.results.map {
                        SnapshotSourceSpeedResponse(
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
        ProbeNodeSnapshotSourcesResult.NotFound ->
            respond(HttpStatusCode.NotFound, NodeSnapshotProbeResponse(ok = false, error = "not_found"))
        ProbeNodeSnapshotSourcesResult.NoSnapshot ->
            respond(
                HttpStatusCode.BadRequest,
                NodeSnapshotProbeResponse(
                    ok = false,
                    error = "no_snapshot",
                    message = "This network/env does not use snapshots",
                ),
            )
        ProbeNodeSnapshotSourcesResult.ServerNotFound ->
            respond(HttpStatusCode.BadRequest, NodeSnapshotProbeResponse(ok = false, error = "server_not_found"))
        ProbeNodeSnapshotSourcesResult.AgentUnreachable ->
            respond(
                NodeSnapshotProbeResponse(
                    ok = false,
                    error = "agent_unreachable",
                    message = "Host agent did not answer — cannot probe snapshot speed",
                ),
            )
    }
}

private fun SnapshotSourceOption.toResponse(): SnapshotSourceResponse =
    SnapshotSourceResponse(
        id = id,
        label = label,
        url = url,
        version = version,
        sizeBytes = sizeBytes,
        streamUnpack = streamUnpack,
        available = available,
        detail = detail,
    )
