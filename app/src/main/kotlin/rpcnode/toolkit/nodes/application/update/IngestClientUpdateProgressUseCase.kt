package rpcnode.toolkit.nodes.application.update

import java.time.Instant
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.domain.model.NodeStatus
import rpcnode.toolkit.nodes.domain.repository.NodeRepository
import rpcnode.toolkit.nodes.application.ingest.authorizeAgentServer
import rpcnode.toolkit.servers.domain.repository.ServerRepository

sealed interface IngestClientUpdateProgressResult
{
    data class Ok(val nodeId: String, val info: ClientUpdateInfo) : IngestClientUpdateProgressResult
    data object Unauthorized : IngestClientUpdateProgressResult
    data object NotFound : IngestClientUpdateProgressResult
}

/**
 * Host agent webhook during client update — stops / updating / started (and errors).
 * Panel caches the snapshot for the admin modal; node status follows stop / start milestones.
 */
class IngestClientUpdateProgressUseCase(
    private val servers: ServerRepository,
    private val nodes: NodeRepository,
    private val store: ClientUpdateProgressStore,
    private val clock: () -> String = { Instant.now().toString() },
)
{
    data class Event(
        val id: String = "",
        val label: String = "",
        val detail: String = "",
        val at: String = "",
    )

    data class Payload(
        val nodeId: String,
        val phase: String = "",
        val step: String = "",
        val detail: String = "",
        val pct: Int = 0,
        val local: String = "",
        val latest: String = "",
        val previousVersion: String = "",
        val updateAvailable: Boolean = false,
        val lastError: String = "",
        val logTail: String = "",
        val event: Event? = null,
    )

    suspend operator fun invoke(
        tokenRaw: String,
        serverIdRaw: String,
        payload: Payload,
    ): IngestClientUpdateProgressResult
    {
        val server = authorizeAgentServer(servers, tokenRaw, serverIdRaw)
            ?: return IngestClientUpdateProgressResult.Unauthorized
        val nodeId = NodeId.parse(payload.nodeId.trim())
            ?: return IngestClientUpdateProgressResult.NotFound
        val node = nodes.findById(nodeId) ?: return IngestClientUpdateProgressResult.NotFound
        if (node.serverId != server.id)
        {
            return IngestClientUpdateProgressResult.Unauthorized
        }

        val now = clock()
        val event = payload.event?.takeIf { it.id.isNotBlank() }?.let {
            ClientUpdateEvent(
                id = it.id.trim().lowercase(),
                label = it.label.trim().ifEmpty { defaultLabel(it.id) },
                detail = it.detail.trim().ifEmpty { payload.detail.trim() },
                at = it.at.trim().ifEmpty { now },
            )
        }
        val info = store.put(
            nodeId.value,
            ClientUpdateInfo(
                local = payload.local,
                latest = payload.latest,
                previousVersion = payload.previousVersion,
                updateAvailable = payload.updateAvailable,
                phase = payload.phase,
                step = payload.step,
                detail = payload.detail,
                pct = payload.pct,
                lastError = payload.lastError,
                logTail = payload.logTail,
                events = listOfNotNull(event),
            ),
        )

        val step = payload.step.trim().lowercase().ifEmpty {
            event?.id.orEmpty()
        }
        when (step)
        {
            "stopped" -> nodes.updateStatus(nodeId, NodeStatus.STOPPED, now)
            "started", "done" ->
            {
                if (!payload.phase.equals("error", ignoreCase = true))
                {
                    nodes.updateStatus(nodeId, NodeStatus.SYNC, now)
                    val ver = payload.local.trim()
                    if (ver.isNotEmpty())
                    {
                        nodes.updateClientVersion(
                            id = nodeId,
                            clientVersion = ver,
                            clientLatest = payload.latest.trim().ifEmpty { node.clientLatest.ifEmpty { ver } },
                            clientUpdateAvailable = payload.updateAvailable,
                            updatedAt = now,
                        )
                    }
                }
            }
        }

        return IngestClientUpdateProgressResult.Ok(nodeId.value, info)
    }

    private fun defaultLabel(id: String): String =
        when (id.trim().lowercase())
        {
            "stopped" -> "Stopped"
            "updating" -> "Updating"
            "started" -> "Started"
            "error" -> "Failed"
            else -> id
        }
}
