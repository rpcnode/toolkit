package rpcnode.toolkit.agent.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.application.client.NotifyPanelClientUpdate
import rpcnode.toolkit.agent.application.node.NodeHeightItem
import rpcnode.toolkit.agent.application.node.NotifyPanelNodeStarted
import rpcnode.toolkit.agent.application.node.PushPanelNodeHeights

class HttpPanelNodeEventsClient(
    private val timeout: Duration = Duration.ofSeconds(12),
) : NotifyPanelNodeStarted, PushPanelNodeHeights, NotifyPanelClientUpdate
{
    private val log = LoggerFactory.getLogger(HttpPanelNodeEventsClient::class.java)
    private val json = Json { encodeDefaults = true }
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(timeout)
        .build()

    override suspend fun invoke(
        panelUrl: String,
        token: String,
        serverId: String,
        nodeId: String,
        pid: Long,
        clientVersion: String,
    ): Boolean = withContext(Dispatchers.IO) {
        post(
            url = panelUrl.trimEnd('/') + "/api/agent/v1/nodes/started",
            token = token,
            body = json.encodeToString(
                StartedBody(
                    serverId = serverId,
                    nodeId = nodeId,
                    pid = pid,
                    clientVersion = clientVersion,
                ),
            ),
        )
    }

    override suspend fun invoke(
        panelUrl: String,
        token: String,
        serverId: String,
        items: List<NodeHeightItem>,
    ): Boolean = withContext(Dispatchers.IO) {
        post(
            url = panelUrl.trimEnd('/') + "/api/agent/v1/nodes/height",
            token = token,
            body = json.encodeToString(
                HeightsBody(
                    serverId = serverId,
                    items = items.map {
                        HeightItemBody(
                            nodeId = it.nodeId,
                            height = it.height,
                            clientVersion = it.clientVersion,
                            sizeOnDisk = it.sizeOnDisk,
                            syncPct = it.syncPct,
                            syncing = it.syncing,
                        )
                    },
                ),
            ),
        )
    }

    override suspend fun invoke(
        panelUrl: String,
        token: String,
        serverId: String,
        nodeId: String,
        phase: String,
        step: String,
        detail: String,
        pct: Int,
        local: String,
        latest: String,
        previousVersion: String,
        updateAvailable: Boolean,
        lastError: String,
        logTail: String,
        eventId: String,
        eventLabel: String,
    ): Boolean = withContext(Dispatchers.IO) {
        post(
            url = panelUrl.trimEnd('/') + "/api/agent/v1/nodes/client-update",
            token = token,
            body = json.encodeToString(
                ClientUpdateProgressBody(
                    serverId = serverId,
                    nodeId = nodeId,
                    phase = phase,
                    step = step,
                    detail = detail,
                    pct = pct,
                    local = local,
                    latest = latest,
                    previousVersion = previousVersion,
                    updateAvailable = updateAvailable,
                    lastError = lastError,
                    logTail = logTail,
                    event = ClientUpdateEventBody(
                        id = eventId,
                        label = eventLabel,
                        detail = detail,
                    ),
                ),
            ),
        )
    }

    private fun post(url: String, token: String, body: String): Boolean
    {
        return try
        {
            val req = HttpRequest.newBuilder(URI(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .header("X-Api-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200 until 300)
            {
                log.warn("POST {} → {}", url, resp.statusCode())
                false
            }
            else
            {
                true
            }
        }
        catch (e: Exception)
        {
            log.warn("POST {} → fail: {}", url, e.message)
            false
        }
    }
}

@Serializable
private data class StartedBody(
    @SerialName("server_id") val serverId: String,
    @SerialName("node_id") val nodeId: String,
    val pid: Long = 0,
    @SerialName("client_version") val clientVersion: String = "",
)

@Serializable
private data class HeightItemBody(
    @SerialName("node_id") val nodeId: String,
    val height: Long,
    @SerialName("client_version") val clientVersion: String = "",
    @SerialName("size_on_disk") val sizeOnDisk: Long = -1,
    @SerialName("sync_pct") val syncPct: Double? = null,
    val syncing: Boolean = false,
)

@Serializable
private data class HeightsBody(
    @SerialName("server_id") val serverId: String,
    val items: List<HeightItemBody> = emptyList(),
)

@Serializable
private data class ClientUpdateEventBody(
    val id: String = "",
    val label: String = "",
    val detail: String = "",
    val at: String = "",
)

@Serializable
private data class ClientUpdateProgressBody(
    @SerialName("server_id") val serverId: String,
    @SerialName("node_id") val nodeId: String,
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
    val event: ClientUpdateEventBody? = null,
)
