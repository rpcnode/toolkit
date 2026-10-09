package rpcnode.toolkit.nodes.application.sysctl

import rpcnode.toolkit.nodes.domain.model.HostSysctlCatalog

fun interface HostSysctlReader
{
    /** GET /api/v1/host/sysctl on the host agent. Null when unreachable. */
    suspend fun read(agentUrl: String, token: String): HostSysctlCatalog?
}
