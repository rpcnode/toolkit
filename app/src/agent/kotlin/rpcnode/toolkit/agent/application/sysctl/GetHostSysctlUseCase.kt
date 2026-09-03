package rpcnode.toolkit.agent.application.sysctl

import rpcnode.toolkit.agent.domain.model.HostSysctlSnapshot

fun interface HostSysctlProbe
{
    fun snapshot(): HostSysctlSnapshot
}

class GetHostSysctlUseCase(
    private val probe: HostSysctlProbe,
)
{
    operator fun invoke(): HostSysctlSnapshot = probe.snapshot()
}
