package rpcnode.toolkit.agent.application.enroll

import rpcnode.toolkit.agent.domain.model.PanelEnrollment

interface PanelEnrollmentStore
{
    suspend fun read(): PanelEnrollment?

    suspend fun write(enrollment: PanelEnrollment)

    suspend fun clear()
}

sealed interface EnrollPanelResult
{
    data class Ok(val enrollment: PanelEnrollment) : EnrollPanelResult
    data object MissingPanelUrl : EnrollPanelResult
    data object MissingServerId : EnrollPanelResult
    data object PanelUnreachable : EnrollPanelResult
    data class StoreFailed(val detail: String) : EnrollPanelResult
}

class EnrollPanelUseCase(
    private val store: PanelEnrollmentStore,
    private val probePanel: ProbePanel,
)
{
    suspend operator fun invoke(panelUrlRaw: String, serverIdRaw: String, ingestPathRaw: String = ""): EnrollPanelResult
    {
        val panelUrl = panelUrlRaw.trim().trimEnd('/')
        if (panelUrl.isEmpty())
        {
            return EnrollPanelResult.MissingPanelUrl
        }
        val serverId = serverIdRaw.trim()
        if (serverId.isEmpty())
        {
            return EnrollPanelResult.MissingServerId
        }
        if (!probePanel.reachable(panelUrl))
        {
            return EnrollPanelResult.PanelUnreachable
        }
        val ingestPath = ingestPathRaw.trim().ifBlank { PanelEnrollment.DEFAULT_INGEST_PATH }
        val path = if (ingestPath.startsWith("/")) ingestPath else "/$ingestPath"
        val enrollment = PanelEnrollment(
            panelUrl = panelUrl,
            serverId = serverId,
            ingestPath = path,
        )
        return try
        {
            store.write(enrollment)
            EnrollPanelResult.Ok(enrollment)
        }
        catch (e: Exception)
        {
            if (e is kotlinx.coroutines.CancellationException) throw e
            EnrollPanelResult.StoreFailed(e.message?.ifBlank { null } ?: e.javaClass.simpleName)
        }
    }
}
