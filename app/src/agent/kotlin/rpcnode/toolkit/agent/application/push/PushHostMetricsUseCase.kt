package rpcnode.toolkit.agent.application.push

import rpcnode.toolkit.agent.application.enroll.PanelEnrollmentStore
import rpcnode.toolkit.agent.application.metrics.CollectHostMetricsUseCase
import rpcnode.toolkit.agent.domain.model.HostMetrics

fun interface PanelMetricsClient
{
    suspend fun post(ingestUrl: String, token: String, serverId: String, metrics: HostMetrics): Boolean
}

class PushHostMetricsUseCase(
    private val enrollment: PanelEnrollmentStore,
    private val collect: CollectHostMetricsUseCase,
    private val client: PanelMetricsClient,
    private val token: String,
)
{
    suspend operator fun invoke(): Boolean
    {
        val dest = enrollment.read() ?: return false
        if (token.isBlank())
        {
            return false
        }
        return client.post(dest.ingestUrl(), token, dest.serverId, collect())
    }
}
