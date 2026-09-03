package rpcnode.toolkit.agent.domain.model

data class PanelEnrollment(
    val panelUrl: String,
    val serverId: String,
    val ingestPath: String = DEFAULT_INGEST_PATH,
)
{
    fun ingestUrl(): String = panelUrl.trimEnd('/') + ingestPath

    companion object
    {
        const val DEFAULT_INGEST_PATH = "/api/agent/v1/metrics"
    }
}
