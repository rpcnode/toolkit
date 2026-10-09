package rpcnode.toolkit.agent.application.metrics

import rpcnode.toolkit.agent.domain.model.HostMetrics

fun interface HostMetricsSource
{
    fun snapshot(): HostMetrics
}

class CollectHostMetricsUseCase(
    private val source: HostMetricsSource,
)
{
    operator fun invoke(): HostMetrics = source.snapshot()
}
